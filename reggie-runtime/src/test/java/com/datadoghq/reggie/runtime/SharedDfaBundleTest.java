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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Per-NFA setup sharing: matchers built for the same NFA must reuse the lazily-materialized DFA
 * caches ({@link PikeVMMatcher.DfaBundle}) and the fully-initialized NFA-derived tables of {@link
 * BitStateMatcher.Bundle} (the per-op matcher construction otherwise recomputes the warm setup on
 * every compile — the dominant cost when a service compiles per operation), while every
 * matcher-written buffer, counter, and lazily-instantiated delegate stays per matcher. Concurrent
 * first use and concurrent population of one shared setup must stay correct.
 *
 * <p>BitState coverage mirrors the PikeVM coverage plus the hybrid path: a standalone {@code
 * BITSTATE_CAPTURE} fixture and the GO-shaped hybrid fixture (route-pinned by {@code
 * BitStateCompileFindBenchmark}) are each compiled twice; the two fresh matchers must share every
 * bundle array and the eligible reject-DFA bundle/cache/step, but nothing the matcher writes during
 * matching. Direct (no-bundle) construction, counted-loop rejection, cache-clearing lifecycle, and
 * JDK-oracle agreement for the GO-shaped fixture across the find/match/bounded API surface are
 * pinned here as well.
 *
 * <p>The shared bundles are held through {@link SoftReference} (bounded retention under heap
 * pressure — see RuntimeCompiler's cache entries); the eviction tests pin the rebuild-on-clear
 * path that makes that sound.
 */
class SharedDfaBundleTest {

  /**
   * Standalone {@code BITSTATE_CAPTURE} fixture: routes to {@code PrefilteringMatcher ->
   * BitStateMatcher} with no hybrid wrapper (verified via {@link #standaloneBitStateOf}, which
   * fails on route drift). Reject-DFA eligible (no assertions; the over-approximation cannot match
   * empty).
   */
  private static final String BITSTATE_PATTERN = "(?:x|y)+?z";

  /**
   * GO-shaped hybrid fixture (the same route-pinned pattern as {@code
   * BitStateCompileFindBenchmark}): {@code HYBRID_DFA} whose NFA half is a {@code BitStateMatcher}
   * built from the cached {@code HybridEntry}'s shared bundle. The exact production GO pattern is
   * deterministic-chain at this HEAD, so this variant is the representative that actually reaches
   * BitState.
   */
  private static final String GO_PATTERN = "^((?:x|y)+?)\\.([^/]+)";

  /** Production-shaped matching input for the GO fixture (short x/y run, dot, slash-free tail). */
  private static final String GO_HIT_INPUT =
      "xyxyyxxyxyx.service_registry.node07.eu-west1.checksum_a91f";

  // NFA-derived, read-only bundle arrays that every matcher of one cache entry must alias.
  private static final String[] SHARED_NFA_DERIVED_ARRAYS = {
    "statesById",
    "isAccept",
    "epsilonTargets",
    "transitionCharSets",
    "transitionTargets",
    "anchorBySid",
    "enterGroupBySid",
    "exitGroupBySid",
    "greedyLoopMid",
    "greedyLoopExit",
  };

  // Matcher-written arrays that must never be taken from (or alias anything in) a bundle.
  private static final String[] MATCHER_WRITTEN_ARRAYS = {
    "caps", "winCaptures", "stackA", "stackB", "stackC", "visited", "localizeScratch",
  };

  private static NFA nfa(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    return new ThompsonBuilder(true).build(ast, countGroups(pattern));
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean inClass = false;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '(' && !inClass) {
        // Count only capturing groups: skip "(?:", "(?=", "(?!", "(?<=", "(?<!" and
        // "(?#"; keep named groups "(?<name>" capturing.
        boolean capturing = true;
        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
          capturing =
              i + 2 < pattern.length()
                  && pattern.charAt(i + 2) == '<'
                  && i + 3 < pattern.length()
                  && pattern.charAt(i + 3) != '='
                  && pattern.charAt(i + 3) != '!';
        }
        if (capturing) {
          count++;
        }
      } else if (c == ')' && !inClass) {
        // no-op; group counting only needs open parens
      }
    }
    return count;
  }

  // -------------------------------------------------------------------------
  // Reflection helpers (same package, but the probed fields are private)
  // -------------------------------------------------------------------------

  private static final ConcurrentHashMap<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

  private static Object field(Object owner, String name) throws Exception {
    String key = owner.getClass().getName() + "#" + name;
    Field f = FIELD_CACHE.get(key);
    if (f == null) {
      Field fresh = owner.getClass().getDeclaredField(name);
      fresh.setAccessible(true);
      Field raced = FIELD_CACHE.putIfAbsent(key, fresh);
      f = raced != null ? raced : fresh;
    }
    return f.get(owner);
  }

  private static int intOf(Object owner, String name) throws Exception {
    return (Integer) field(owner, name);
  }

  private static LazyDFACache findDfa(PikeVMMatcher matcher) throws Exception {
    Field f = PikeVMMatcher.class.getDeclaredField("findDfa");
    f.setAccessible(true);
    return (LazyDFACache) f.get(matcher);
  }

  private static LazyDFACache rejectDfa(PikeVMMatcher matcher) throws Exception {
    Field f = PikeVMMatcher.class.getDeclaredField("rejectDfa");
    f.setAccessible(true);
    return (LazyDFACache) f.get(matcher);
  }

  private static LazyDFACache rejectDfa(BitStateMatcher matcher) throws Exception {
    Field f = BitStateMatcher.class.getDeclaredField("rejectDfa");
    f.setAccessible(true);
    return (LazyDFACache) f.get(matcher);
  }

  // -------------------------------------------------------------------------
  // Wrapper/hybrid unwrapping helpers
  // -------------------------------------------------------------------------

  /**
   * Strips the runtime-internal wrapper layers ({@code PrefilteringMatcher} exposes a
   * package-private {@code delegate()}; {@code NameEnrichingMatcher} keeps its delegate private, so
   * that hop is reflective — same style as the existing reflective helpers above).
   */
  private static ReggieMatcher unwrapWrappers(ReggieMatcher m) throws Exception {
    while (true) {
      if (m instanceof PrefilteringMatcher p) {
        m = p.delegate();
      } else if (m instanceof NameEnrichingMatcher) {
        m = (ReggieMatcher) field(m, "delegate");
      } else {
        return m;
      }
    }
  }

  /**
   * Unwraps a compiled {@link #BITSTATE_PATTERN} result down to its bare {@link BitStateMatcher} —
   * doubling as the route guard that the standalone fixture does not silently reroute (e.g. to a
   * hybrid or deterministic chain).
   */
  private static BitStateMatcher standaloneBitStateOf(ReggieMatcher compiled) throws Exception {
    ReggieMatcher engine = unwrapWrappers(compiled);
    assertTrue(
        engine instanceof BitStateMatcher,
        BITSTATE_PATTERN
            + " must compile to a standalone BitStateMatcher, got "
            + engine.getClass().getSimpleName()
            + " (route drift)");
    return (BitStateMatcher) engine;
  }

  /**
   * Unwraps wrapper layers and then the {@code HybridMatcher.nfaMatcher} half, asserting it is
   * BitState-backed — the same proof {@code BitStateCompileFindBenchmark}'s setup makes for the
   * benchmark route.
   */
  private static BitStateMatcher hybridBitStateHalfOf(ReggieMatcher compiled) throws Exception {
    ReggieMatcher engine = unwrapWrappers(compiled);
    assertTrue(
        engine instanceof HybridMatcher,
        GO_PATTERN
            + " must compile to a HybridMatcher, got "
            + engine.getClass().getSimpleName()
            + " (route drift)");
    ReggieMatcher nfaHalf = (ReggieMatcher) field(engine, "nfaMatcher");
    assertTrue(
        nfaHalf instanceof BitStateMatcher,
        "hybrid NFA half must be BitState-backed, got "
            + nfaHalf.getClass().getSimpleName()
            + " (route drift)");
    return (BitStateMatcher) nfaHalf;
  }

  // -------------------------------------------------------------------------
  // Bundle sharing / isolation assertion helpers
  // -------------------------------------------------------------------------

  /**
   * Asserts {@code m1} and {@code m2} alias one shared, fully-initialized bundle: every NFA-derived
   * array by identity, the eligible reject-DFA cache and step by identity, and the primitive
   * metadata/filter results by value. (The matcher keeps no direct bundle reference — it aliases
   * the bundle's final fields — so identical identities across all of them prove the same bundle.)
   */
  private static void assertOneSharedBundleSetup(
      BitStateMatcher m1, BitStateMatcher m2, String label) throws Exception {
    for (String name : SHARED_NFA_DERIVED_ARRAYS) {
      assertSame(
          field(m1, name),
          field(m2, name),
          label + ": " + name + " must be the shared bundle's array");
    }
    Object reject1 = field(m1, "rejectDfa");
    assertNotNull(reject1, label + ": fixture must be reject-DFA eligible");
    assertSame(
        reject1, field(m2, "rejectDfa"), label + ": reject LazyDFACache must be the shared one");
    assertSame(
        field(m1, "rejectStep"),
        field(m2, "rejectStep"),
        label + ": reject NfaStep must be the shared one");
    assertEquals(intOf(m1, "groupCount"), intOf(m2, "groupCount"), label + ": groupCount");
    assertEquals(intOf(m1, "stateCount"), intOf(m2, "stateCount"), label + ": stateCount");
    assertEquals(intOf(m1, "startStateId"), intOf(m2, "startStateId"), label + ": startStateId");
    assertEquals(
        intOf(m1, "singleFirstCharAscii"),
        intOf(m2, "singleFirstCharAscii"),
        label + ": singleFirstCharAscii filter result");
  }

  /** Asserts every matcher-written array is a fresh allocation and the matchers are distinct. */
  private static void assertFreshMatcherWrittenState(
      BitStateMatcher m1, BitStateMatcher m2, String label) throws Exception {
    assertNotSame(m1, m2, label + ": compile() must return a fresh matcher per call");
    for (String name : MATCHER_WRITTEN_ARRAYS) {
      assertNotSame(field(m1, name), field(m2, name), label + ": " + name + " must be per-matcher");
    }
    Object laurikari1 = field(m1, "laurikari");
    Object laurikari2 = field(m2, "laurikari");
    if (laurikari1 != null || laurikari2 != null) {
      assertNotSame(
          laurikari1, laurikari2, label + ": Laurikari matcher must be per-matcher, not bundled");
    }
  }

  private static void assertSpansEqual(
      java.util.regex.Matcher oracle, MatchResult r, String label) {
    assertEquals(oracle.groupCount(), r.groupCount(), label + ": groupCount");
    for (int g = 0; g <= oracle.groupCount(); g++) {
      assertEquals(oracle.start(g), r.start(g), label + ": group " + g + " start");
      assertEquals(oracle.end(g), r.end(g), label + ": group " + g + " end");
    }
  }

  /** Boolean form of {@link #assertSpansEqual} for the concurrent stress worker. */
  private static boolean spansMatch(java.util.regex.Matcher oracle, MatchResult r) {
    if (oracle.groupCount() != r.groupCount()) {
      return false;
    }
    for (int g = 0; g <= oracle.groupCount(); g++) {
      if (oracle.start(g) != r.start(g) || oracle.end(g) != r.end(g)) {
        return false;
      }
    }
    return true;
  }

  // -------------------------------------------------------------------------
  // PikeVM bundle sharing (existing coverage, preserved)
  // -------------------------------------------------------------------------

  @Test
  void matchersOfOneBundleShareDfaCaches() throws Exception {
    // billing-style pattern: eligible for the exact findDfa
    String pattern = "(?:^|,)[hH][oO][sS][tT]:[a-zA-Z_0-9]+";
    NFA nfa = nfa(pattern);
    PikeVMMatcher.DfaBundle bundle = new PikeVMMatcher.DfaBundle(nfa);
    assertNotNull(bundle.findDfa, "pattern should be findDfa-eligible");
    PikeVMMatcher m1 = new PikeVMMatcher(nfa, pattern, bundle);
    PikeVMMatcher m2 = new PikeVMMatcher(nfa, pattern, bundle);
    assertSame(bundle.findDfa, findDfa(m1));
    assertSame(bundle.findDfa, findDfa(m2));
    // Review #140 r4165029351: matchers must retain the bundle itself, so the entry's
    // SoftReference cannot be GC-cleared while live matchers still pin the bundle's caches.
    assertSame(bundle, sourceBundle(m1));
    assertSame(bundle, sourceBundle(m2));
    assertTrue(m1.matches("host:abc"));
    assertTrue(m2.matches(",host:9"));
  }

  /** The matcher-held bundle reachability anchor (PikeVMMatcher.sourceBundle). */
  private static PikeVMMatcher.DfaBundle sourceBundle(PikeVMMatcher m) throws Exception {
    Field f = PikeVMMatcher.class.getDeclaredField("sourceBundle");
    f.setAccessible(true);
    return (PikeVMMatcher.DfaBundle) f.get(m);
  }

  /**
   * {@link RejectDfaFactory#NONE} marks a known-ineligible NFA: BitStateMatcher must skip its
   * matcher-private build retry (review #140 r4165029362) and run without a reject DFA.
   */
  @Test
  void noneSentinelSkipsPrivateRejectBuild() throws Exception {
    String pattern = "\\bhost:[0-9]+";
    NFA nfa = nfa(pattern);
    BitStateMatcher m = new BitStateMatcher(nfa, pattern, null, RejectDfaFactory.NONE);
    Field f = BitStateMatcher.class.getDeclaredField("rejectDfa");
    f.setAccessible(true);
    assertNull(f.get(m), "NONE must resolve to no reject DFA, not a private rebuild");
    assertTrue(m.matches("host:123"));
    assertFalse(m.matches("host:abc"));
    assertFalse(m.matches("noservicehere"));
  }

  @Test
  void matchersWithoutBundleBuildPrivateCaches() throws Exception {
    String pattern = "(?:^|,)[hH][oO][sS][tT]:[a-zA-Z_0-9]+";
    NFA nfa = nfa(pattern);
    PikeVMMatcher m1 = new PikeVMMatcher(nfa, pattern);
    PikeVMMatcher m2 = new PikeVMMatcher(nfa, pattern);
    assertNotNull(findDfa(m1));
    assertNotNull(findDfa(m2));
    assertTrue(findDfa(m1) != findDfa(m2), "no-bundle ctors must not share caches");
  }

  @Test
  void anchoredPatternsShareRejectDfa() throws Exception {
    // word-boundary anchored: findDfa ineligible (needs char context), reject DFA built
    String pattern = "\\bhost:[0-9]+";
    NFA nfa = nfa(pattern);
    PikeVMMatcher.DfaBundle bundle = new PikeVMMatcher.DfaBundle(nfa);
    assertNotNull(bundle.rejectDfa, "anchored assertion-free pattern should get a reject DFA");
    PikeVMMatcher m1 = new PikeVMMatcher(nfa, pattern, bundle);
    PikeVMMatcher m2 = new PikeVMMatcher(nfa, pattern, bundle);
    assertSame(bundle.rejectDfa, rejectDfa(m1));
    assertSame(bundle.rejectDfa, rejectDfa(m2));
  }

  @Test
  void bitStateMatchersShareRejectBundle() throws Exception {
    String pattern = "^(.+?)\\.([^/]+)";
    NFA nfa = nfa(pattern);
    RejectDfaFactory.Bundle bundle = RejectDfaFactory.build(nfa);
    assertNotNull(bundle, "lazy-group pattern should get a reject bundle");
    BitStateMatcher m1 = new BitStateMatcher(nfa, pattern, null, bundle);
    BitStateMatcher m2 = new BitStateMatcher(nfa, pattern, null, bundle);
    assertSame(bundle.dfa, rejectDfa(m1));
    assertSame(bundle.dfa, rejectDfa(m2));
  }

  // -------------------------------------------------------------------------
  // BitState bundle sharing: standalone and hybrid cached paths
  // -------------------------------------------------------------------------

  @Test
  void standaloneBitStateCompilesShareOneBundleSetup() throws Exception {
    BitStateMatcher m1 = standaloneBitStateOf(RuntimeCompiler.compile(BITSTATE_PATTERN));
    BitStateMatcher m2 = standaloneBitStateOf(RuntimeCompiler.compile(BITSTATE_PATTERN));
    assertOneSharedBundleSetup(m1, m2, "standalone BITSTATE_CAPTURE");
    assertFreshMatcherWrittenState(m1, m2, "standalone BITSTATE_CAPTURE");
    // Both fresh matchers still work.
    Pattern jdk = Pattern.compile(BITSTATE_PATTERN);
    assertEquals(jdk.matcher("aqxxyz.b").find(), m1.find("aqxxyz.b"));
    assertEquals(jdk.matcher("xyz").matches(), m2.matches("xyz"));
  }

  @Test
  void hybridBitStateCompilesShareOneBundleSetup() throws Exception {
    ReggieMatcher r1 = RuntimeCompiler.compile(GO_PATTERN);
    ReggieMatcher r2 = RuntimeCompiler.compile(GO_PATTERN);
    BitStateMatcher m1 = hybridBitStateHalfOf(r1);
    BitStateMatcher m2 = hybridBitStateHalfOf(r2);
    assertOneSharedBundleSetup(m1, m2, "hybrid NFA half");
    assertFreshMatcherWrittenState(m1, m2, "hybrid NFA half");
    // The stateless DFA half stays owned by the cache entry (shared by design, never written
    // during matching); only the NFA half is per-compile — pin that distinction.
    assertSame(
        field(unwrapWrappers(r1), "dfaMatcher"),
        field(unwrapWrappers(r2), "dfaMatcher"),
        "the hybrid's stateless DFA half is the entry-owned half");
    // Both fresh NFA halves still produce JDK-identical captures.
    java.util.regex.Matcher jm = Pattern.compile(GO_PATTERN).matcher(GO_HIT_INPUT);
    assertTrue(jm.find());
    MatchResult hit = m1.findMatch(GO_HIT_INPUT);
    assertTrue(hit != null, "first hybrid half must find the GO hit");
    assertSpansEqual(jm, hit, "hybrid half 1");
    MatchResult hit2 = m2.findMatch(GO_HIT_INPUT);
    assertTrue(hit2 != null, "second hybrid half must find the GO hit");
    assertSpansEqual(jm, hit2, "hybrid half 2");
  }

  /**
   * Criterion 2's behavioral half: exercise one of two bundle-sharing matchers hard (hits, misses,
   * an over-budget delegation, grown visited/captures/counters) and prove the other's
   * captures/counters/generations stay exactly as it was, then exercise it independently and prove
   * the direction holds both ways.
   */
  @Test
  void oneCompiledBitStateMatchersCallsDoNotAlterTheOther() throws Exception {
    Pattern jdk = Pattern.compile(GO_PATTERN);
    BitStateMatcher m1 = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
    BitStateMatcher m2 = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));

    // Baseline: m2 has never been used — all-zero caps/winCaptures, no generation, no stack, no
    // counters (compile() itself must not have run a search on it).
    int[] m2Caps = (int[]) field(m2, "caps");
    int[] m2Win = (int[]) field(m2, "winCaptures");
    for (int v : m2Caps) {
      assertEquals(0, v, "m2 caps must be untouched before first use");
    }
    for (int v : m2Win) {
      assertEquals(0, v, "m2 winCaptures must be untouched before first use");
    }
    assertEquals(0, intOf(m2, "visitedGeneration"), "m2 visited generation untouched");
    assertEquals(0, intOf(m2, "stackTop"), "m2 stack top untouched");
    assertEquals(0, ((int[]) field(m2, "visited")).length, "m2 visited not grown by m1's searches");
    assertEquals(0, m2.fallbackCount(), "m2 fallback counter untouched");
    assertEquals(0, m2.laurikariCount(), "m2 Laurikari counter untouched");

    // Exercise m1: capture-producing hit, a miss, and an over-budget call that must delegate
    // (to the per-matcher Laurikari matcher or PikeVM fallback — whichever the fixture gets).
    java.util.regex.Matcher jm1 = jdk.matcher(GO_HIT_INPUT);
    assertTrue(jm1.find());
    MatchResult r1 = m1.findMatch(GO_HIT_INPUT);
    assertTrue(r1 != null);
    assertSpansEqual(jm1, r1, "m1 hit");
    assertFalse(m1.find("no match here 1"), "m1 miss");
    String overBudgetMiss = "q".repeat(60_000);
    assertEquals(jdk.matcher(overBudgetMiss).matches(), m1.matches(overBudgetMiss));
    assertTrue(
        m1.fallbackCount() + m1.laurikariCount() > 0,
        "over-budget call must delegate to a per-matcher fallback/Laurikari instance");
    assertTrue(intOf(m1, "visitedGeneration") > 0, "m1 must have run its own searches");
    Object m1Visited = field(m1, "visited");
    assertTrue(((int[]) m1Visited).length > 0, "m1 visited must be grown by its searches");
    Object m1Fallback = field(m1, "fallback");
    Object m1Laurikari = field(m1, "laurikari");
    if (m1Laurikari != null) {
      assertNotSame(m1Laurikari, field(m2, "laurikari"), "Laurikari instances must be per-matcher");
    }
    if (m1Fallback != null) {
      assertNull(field(m2, "fallback"), "m1's budget overflow must not instantiate m2's fallback");
    }

    // Everything m1 did must have left m2 exactly at its baseline.
    assertEquals(
        0, intOf(m2, "visitedGeneration"), "m2 generation still untouched after m1's work");
    assertEquals(0, intOf(m2, "stackTop"), "m2 stack top still untouched after m1's work");
    assertEquals(0, ((int[]) field(m2, "visited")).length, "m2 visited still not grown");
    assertEquals(0, m2.fallbackCount(), "m2 fallback counter still untouched");
    assertEquals(0, m2.laurikariCount(), "m2 Laurikari counter still untouched");
    for (int v : m2Caps) {
      assertEquals(0, v, "m2 caps still untouched");
    }
    for (int v : m2Win) {
      assertEquals(0, v, "m2 winCaptures still untouched");
    }

    // Now exercise m2 independently and prove it does not perturb m1 either.
    long m1FallbackCount = m1.fallbackCount();
    long m1LaurikariCount = m1.laurikariCount();
    int m1Generation = intOf(m1, "visitedGeneration");
    java.util.regex.Matcher jm2 = jdk.matcher("xy.z");
    assertTrue(jm2.find());
    MatchResult r2 = m2.findMatch("xy.z");
    assertTrue(r2 != null);
    assertSpansEqual(jm2, r2, "m2 independent hit");
    assertFalse(m2.find("xy"), "m2 independent miss");
    assertEquals(
        m1FallbackCount, m1.fallbackCount(), "m2's calls must not touch m1's fallback counter");
    assertEquals(
        m1LaurikariCount, m1.laurikariCount(), "m2's calls must not touch m1's Laurikari counter");
    assertEquals(
        m1Generation, intOf(m1, "visitedGeneration"), "m2's search must not bump m1's generation");
    assertSame(
        m1Visited,
        field(m1, "visited"),
        "m2's search must not replace or alias m1's grown visited");

    // And both still produce JDK-identical spans after the cross-exercise.
    MatchResult r1again = m1.findMatch(GO_HIT_INPUT);
    assertTrue(r1again != null);
    assertSpansEqual(jm1, r1again, "m1 after m2's exercise");
    MatchResult r2again = m2.findMatch("xy.z");
    assertTrue(r2again != null);
    assertSpansEqual(jm2, r2again, "m2 after m1's exercise");
  }

  /**
   * Criterion 3: the no-bundle construction paths used by runtime tests keep a fully private setup
   * (distinct table and reject-cache identities) and still match; the lazily-instantiated PikeVM
   * fallback is per matcher (null Laurikari here, so an over-budget call deterministically takes
   * the fallback route).
   */
  @Test
  void directBitStateConstructionsBuildPrivateSetups() throws Exception {
    NFA nfa = nfa(BITSTATE_PATTERN);
    BitStateMatcher m1 = new BitStateMatcher(nfa, BITSTATE_PATTERN);
    BitStateMatcher m2 = new BitStateMatcher(nfa, BITSTATE_PATTERN);
    for (String name : SHARED_NFA_DERIVED_ARRAYS) {
      assertNotSame(
          field(m1, name),
          field(m2, name),
          "direct no-bundle construction: " + name + " must be matcher-private");
    }
    assertNotSame(
        field(m1, "rejectDfa"),
        field(m2, "rejectDfa"),
        "direct construction must not share the reject LazyDFACache");
    for (String name : MATCHER_WRITTEN_ARRAYS) {
      assertNotSame(
          field(m1, name),
          field(m2, name),
          "direct construction: " + name + " must be per-matcher");
    }

    Pattern jdk = Pattern.compile(BITSTATE_PATTERN);
    java.util.regex.Matcher jm = jdk.matcher("aqxxyz.b");
    assertTrue(jm.find());
    MatchResult r1 = m1.findMatch("aqxxyz.b");
    assertTrue(r1 != null);
    assertSpansEqual(jm, r1, "direct m1");
    MatchResult r2 = m2.findMatch("aqxxyz.b");
    assertTrue(r2 != null);
    assertSpansEqual(jm, r2, "direct m2");
    assertEquals(jdk.matcher("xyz").matches(), m1.matches("xyz"));
    assertEquals(jdk.matcher("xy").matches(), m2.matches("xy"));

    // Over-budget call with no Laurikari: the PikeVM fallback is lazily instantiated on m1
    // only. No JDK oracle on this input — java.util.regex recurses per quantifier iteration and
    // stack-overflows on 60k-length inputs — so assert the obvious whole-match semantics instead:
    // an x-run followed by z fully matches, a bare x-run does not.
    String overBudgetHit = "x".repeat(60_000) + "z";
    assertTrue(
        m1.matches(overBudgetHit), "m1's fallback must still produce the full x-run+z match");
    assertEquals(1, m1.fallbackCount(), "over-budget matches() must delegate to the fallback");
    assertFalse(
        m1.matches("x".repeat(60_000)),
        "a bare x-run is no whole match (second fallback delegation)");
    assertEquals(2, m1.fallbackCount(), "each over-budget call delegates again");
    assertEquals(0, m2.fallbackCount(), "m2's fallback counter must stay untouched");
    assertNotNull(field(m1, "fallback"), "m1's fallback must be instantiated by its own overflow");
    assertNull(field(m2, "fallback"), "m2's fallback must not be instantiated by m1's overflow");
    assertNotSame(
        field(m1, "fallback"), field(m2, "fallback"), "fallback instances must be per-matcher");
    assertEquals(0, m1.laurikariCount(), "no Laurikari was supplied, so none can be delegated to");
  }

  /**
   * Criterion 3's counted-loop contract: {@code a{0,6000}} lowers to a counted loop (tail 6000 x
   * child cost exceeds the 5000-state unroll budget) and {@code BitStateMatcher} — and now also its
   * {@code Bundle} — must keep rejecting it with the unchanged exception.
   */
  @Test
  void countedLoopNfaIsStillRejectedByBitState() throws Exception {
    NFA nfa = nfa("a{0,6000}");
    assertTrue(nfa.hasCountedLoops(), "a{0,6000} must lower to a counted-loop NFA");
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new BitStateMatcher(nfa, "a{0,6000}"));
    assertTrue(
        ex.getMessage().contains("counted-loop"),
        "unchanged counted-loop exception, got: " + ex.getMessage());
    assertThrows(
        IllegalStateException.class,
        () -> new BitStateMatcher.Bundle(nfa),
        "the shared bundle must reject counted-loop NFAs identically (entries are built without a matcher)");
  }

  // -------------------------------------------------------------------------
  // GO-shaped fixture: JDK-oracle agreement across the API surface
  // -------------------------------------------------------------------------

  @Test
  void goShapedHybridAgreesWithJdkAcrossEveryApi() throws Exception {
    Pattern jdk = Pattern.compile(GO_PATTERN);
    ReggieMatcher m = RuntimeCompiler.compile(GO_PATTERN);

    // "xy.xy.z" pins lazy priority: the lazy first capture stops at the FIRST dot ("xy"), where a
    // greedy quantifier would consume "xyxy" first; the span comparison against JDK enforces it.
    String[] hits = {GO_HIT_INPUT, "xy.z", "xy.xy.z", "xy.a/b"};
    String[] misses = {"", "no match here", "xy", "zz.xy", "xy./", "z9.abc"};

    for (String input : hits) {
      java.util.regex.Matcher jm = jdk.matcher(input);
      assertTrue(jm.find(), "expected fixture hit: '" + input + "'");
      assertTrue(m.find(input), "find on: " + input);
      assertEquals(jm.start(), m.findFrom(input, 0), "findFrom(input, 0) on: " + input);
      assertEquals(-1, m.findFrom(input, 1), "anchored ^ cannot match from offset 1: " + input);
      MatchResult r = m.findMatch(input);
      assertTrue(r != null, "findMatch on: " + input);
      assertSpansEqual(jm, r, "findMatch spans (lazy priority included) on: " + input);
      assertEquals(jdk.matcher(input).matches(), m.matches(input), "matches on: " + input);
    }
    for (String input : misses) {
      assertFalse(jdk.matcher(input).find(), "expected fixture miss: '" + input + "'");
      assertFalse(m.find(input), "find miss on: " + input);
      assertEquals(-1, m.findFrom(input, 0), "findFrom miss on: " + input);
      assertTrue(m.findMatch(input) == null, "findMatch miss on: " + input);
      assertEquals(jdk.matcher(input).matches(), m.matches(input), "matches miss on: " + input);
    }

    // Bounded (region) matching where valid. The compiled hybrid delegates bounded calls to its
    // generated DFA half (lazyFind=false for this fixture), which anchors ^ absolutely: at HEAD
    // 967e525 that half already returns false for a region starting at a nonzero offset where
    // java.util.regex (default anchoring bounds) returns true — a pre-existing divergence of the
    // generated DFA half, outside this test's scope (verified on a detached 967e525 worktree:
    // hybrid.matchesBounded(cs,2,6)=false while the BitState NFA half and the JDK both say true).
    // So regions starting at 0 — where both engines anchor ^ at 0 — are compared through the
    // compiled hybrid, and the nonzero-offset region is compared through the hybrid's BitState
    // NFA half, whose subSequence semantics anchor ^ at the region start exactly like the JDK.
    CharSequence cs2 = "xy.a/b";
    java.util.regex.Matcher regionAtZero = jdk.matcher(cs2).region(0, 4);
    assertTrue(regionAtZero.matches(), "region [0,4) of 'xy.a/b' must match");
    assertTrue(m.matchesBounded(cs2, 0, 4), "matchesBounded on the matching region at start 0");
    MatchResult rb = m.matchBounded(cs2, 0, 4);
    assertTrue(rb != null, "matchBounded on the matching region at start 0");
    assertSpansEqual(regionAtZero, rb, "bounded spans at region start 0");
    assertFalse(
        m.matchesBounded(cs2, 0, 5),
        "region ending on the slash cannot match ([^/]+ cannot consume it)");

    CharSequence cs = "  xy.a tail";
    java.util.regex.Matcher regionHit = jdk.matcher(cs).region(2, 6);
    assertTrue(regionHit.matches(), "region [2,6) must match");
    BitStateMatcher half = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
    assertTrue(
        half.matchesBounded(cs, 2, 6), "BitState half matchesBounded on the matching region");
    MatchResult nb = half.matchBounded(cs, 2, 6);
    assertTrue(nb != null, "BitState half matchBounded on the matching region");
    assertSpansEqual(regionHit, nb, "BitState half bounded spans (region-anchored ^)");
    assertFalse(half.matchesBounded(cs, 0, 4), "region before the anchor position cannot match");
    assertFalse(half.matchesBounded(cs, 2, 4), "region without the dot's tail cannot match");
  }

  // -------------------------------------------------------------------------
  // Cache lifecycle
  // -------------------------------------------------------------------------

  @Test
  void clearCacheDropsStandaloneBitStateBundleOwnership() throws Exception {
    BitStateMatcher before1 = standaloneBitStateOf(RuntimeCompiler.compile(BITSTATE_PATTERN));
    BitStateMatcher before2 = standaloneBitStateOf(RuntimeCompiler.compile(BITSTATE_PATTERN));
    assertSame(
        field(before1, "statesById"), field(before2, "statesById"), "pre-clear sanity: one bundle");
    Object beforeReject = field(before1, "rejectDfa");
    assertSame(beforeReject, field(before2, "rejectDfa"), "pre-clear sanity: one reject cache");

    RuntimeCompiler.clearCache();

    BitStateMatcher after = standaloneBitStateOf(RuntimeCompiler.compile(BITSTATE_PATTERN));
    assertNotSame(
        field(before1, "statesById"),
        field(after, "statesById"),
        "clearCache() must drop the entry owning the shared bundle; recompilation rebuilds setup");
    assertNotSame(
        beforeReject,
        field(after, "rejectDfa"),
        "the reject LazyDFACache must not be retained through the cleared entry");
    assertEquals(
        intOf(before1, "stateCount"), intOf(after, "stateCount"), "same pattern, same shape");
    assertTrue(after.find("aqxxyz.b"), "the rebuilt private setup must still match");
  }

  @Test
  void clearCacheDropsHybridBitStateBundleOwnership() throws Exception {
    BitStateMatcher before1 = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
    BitStateMatcher before2 = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
    assertSame(
        field(before1, "statesById"), field(before2, "statesById"), "pre-clear sanity: one bundle");
    Object beforeReject = field(before1, "rejectDfa");
    assertSame(beforeReject, field(before2, "rejectDfa"), "pre-clear sanity: one reject cache");

    RuntimeCompiler.clearCache();

    BitStateMatcher after = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
    assertNotSame(
        field(before1, "statesById"),
        field(after, "statesById"),
        "clearCache() must drop the hybrid entry owning the shared bundle");
    assertNotSame(
        beforeReject,
        field(after, "rejectDfa"),
        "the hybrid's reject LazyDFACache must not be retained through the cleared entry");
    java.util.regex.Matcher jm = Pattern.compile(GO_PATTERN).matcher(GO_HIT_INPUT);
    assertTrue(jm.find());
    MatchResult r = after.findMatch(GO_HIT_INPUT);
    assertTrue(r != null, "the rebuilt hybrid half must still match");
    assertSpansEqual(jm, r, "rebuilt hybrid half");
  }

  // -------------------------------------------------------------------------
  // Concurrency
  // -------------------------------------------------------------------------

  /** Concurrent population of one shared cache across threads must not corrupt results. */
  @Test
  void concurrentUseOfSharedCacheIsCorrect() throws Exception {
    String pattern = "(?:^|,)[hH][oO][sS][tT]:[a-zA-Z_0-9]+";
    java.util.regex.Pattern jdk = Pattern.compile(pattern);
    NFA nfa = nfa(pattern);
    PikeVMMatcher.DfaBundle bundle = new PikeVMMatcher.DfaBundle(nfa);
    int threads = Runtime.getRuntime().availableProcessors();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      @SuppressWarnings("unchecked")
      Future<Boolean>[] results = (Future<Boolean>[]) new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        results[t] =
            pool.submit(
                () -> {
                  start.await();
                  boolean ok = true;
                  for (int i = 0; i < 5_000; i++) {
                    String input = (i % 2 == 0) ? ("x,host:" + i) : ("no match here " + i);
                    PikeVMMatcher m = new PikeVMMatcher(nfa, pattern, bundle);
                    boolean expected = jdk.matcher(input).find();
                    ok &= (m.find(input) == expected);
                  }
                  return ok;
                });
      }
      start.countDown();
      for (Future<Boolean> r : results) {
        assertTrue(r.get(60, TimeUnit.SECONDS), "no thread observed a wrong result");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ── Eviction: a GC-cleared SoftReference in a cache entry must yield a correct rebuilt bundle.
  // \b(a?)+x routes to PIKEVM_CAPTURE and \b(a)+x to BITSTATE_CAPTURE (word boundary skips the
  // hybrid; bitstate eligibility splits the two — verified via RuntimeCompiler.describeRouting).
  private static Object cacheEntry(String cacheField, String pattern) throws Exception {
    Field f = RuntimeCompiler.class.getDeclaredField(cacheField);
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Object, Object> cache = (Map<Object, Object>) f.get(null);
    Object entry = cache.get(pattern);
    assertNotNull(entry, "compile must register the pattern in " + cacheField);
    return entry;
  }

  private static void evictBundle(Object entry, String fieldName) throws Exception {
    Field f = entry.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(entry, new SoftReference<>(null));
  }

  @SuppressWarnings("unchecked")
  private static <T> T referencedBundle(Object entry, String fieldName) throws Exception {
    Field f = entry.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return ((SoftReference<T>) f.get(entry)).get();
  }

  @Test
  void evictedPikeVmBundleIsRebuiltCorrectly() throws Exception {
    String pattern = "\\b(a?)+x";
    assertTrue(Reggie.compile(pattern).matches("ax")); // builds the entry + bundle
    Object entry = cacheEntry("PIKEVM_NFA_CACHE", pattern);
    PikeVMMatcher.DfaBundle original = referencedBundle(entry, "dfaBundle");
    assertNotNull(original, "first compile must have built the bundle strongly");

    evictBundle(entry, "dfaBundle"); // simulate GC having cleared the SoftReference

    ReggieMatcher rebuilt = Reggie.compile(pattern);
    PikeVMMatcher.DfaBundle second = referencedBundle(entry, "dfaBundle");
    assertNotNull(second, "evicted entry must rebuild the bundle, not stay null");
    assertTrue(second != original, "rebuilt bundle must not be the evicted instance");
    assertTrue(rebuilt.matches("ax"));
    assertTrue(rebuilt.matches("x")); // (a?)+ can iterate on the empty string
    assertTrue(rebuilt.find(" ax")); // space->a is a word boundary
    assertFalse(rebuilt.find("yax")); // no word boundary before 'a' mid-word
    assertFalse(rebuilt.matches("a"));
  }

  @Test
  void evictedBitStateBundleIsRebuiltCorrectly() throws Exception {
    String pattern = "\\b(a)+x";
    assertTrue(Reggie.compile(pattern).matches("ax")); // builds the entry + setup bundle
    Object entry = cacheEntry("BITSTATE_NFA_CACHE", pattern);
    BitStateMatcher.Bundle original = referencedBundle(entry, "bundle");
    assertNotNull(original, "first compile must have built the setup bundle strongly");

    evictBundle(entry, "bundle"); // simulate GC having cleared the SoftReference

    ReggieMatcher rebuilt = Reggie.compile(pattern);
    BitStateMatcher.Bundle second = referencedBundle(entry, "bundle");
    assertNotNull(second, "evicted entry must rebuild the setup bundle, not stay null");
    assertTrue(second != original, "rebuilt bundle must not be the evicted instance");
    assertTrue(rebuilt.matches("ax"));
    assertTrue(rebuilt.matches("aaax"));
    assertTrue(rebuilt.find(" aax")); // space->a is a word boundary
    assertFalse(rebuilt.find("yaax")); // no word boundary before 'a' mid-word
    assertFalse(rebuilt.matches("x"));
    assertFalse(rebuilt.matches("a"));
  }

  /**
   * Concurrent first use and population of the shared BitState bundle: after a cache clear, with
   * the hybrid entry established but NO reject-DFA transition prewarmed, all threads are released
   * together; every iteration compiles a fresh runtime matcher (never sharing one), unwraps its
   * fresh BitState NFA half, and executes {@code findMatch} directly on that half — hits and varied
   * misses alike — so the misses drive concurrent lazy population of the one shared reject {@link
   * LazyDFACache} rather than only exercising the hybrid's DFA half. Booleans and every group span
   * are compared to a per-thread JDK oracle, and every fresh half must observe the same
   * reject-cache identity (safe publication under contention).
   */
  @Test
  void concurrentFirstUseOfSharedBitStateBundleIsCorrect() throws Exception {
    Pattern jdk = Pattern.compile(GO_PATTERN);

    RuntimeCompiler.clearCache();
    // Establish the cache entry (and its shared BitStateMatcher.Bundle) WITHOUT prewarming any
    // reject-DFA transition: LazyDFACache populates lazily on the first find-family call, and no
    // find has run on this probe.
    BitStateMatcher probeHalf = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
    LazyDFACache sharedReject = (LazyDFACache) field(probeHalf, "rejectDfa");
    assertNotNull(
        sharedReject,
        "the hybrid's BitState half must be reject-DFA eligible, so direct NFA-half misses"
            + " drive concurrent lazy population of the shared cache");

    int threads = Math.min(8, Math.max(4, Runtime.getRuntime().availableProcessors()));
    // Fixed total operation count regardless of host core count: 5,000+ per thread, capped at
    // 8 workers so high-core CI hosts do not turn 40k into 640k allocation-heavy iterations.
    int iterations = Math.max(5_000, 40_000 / threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      @SuppressWarnings("unchecked")
      Future<Boolean>[] results = (Future<Boolean>[]) new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        results[t] =
            pool.submit(
                () -> {
                  start.await();
                  boolean ok = true;
                  java.util.regex.Matcher jm = jdk.matcher(""); // per-thread JDK oracle
                  for (int i = 0; i < iterations; i++) {
                    String input;
                    if (i % 5 == 0) {
                      input = GO_HIT_INPUT; // capture-producing hit
                    } else if (i % 5 == 1) {
                      input = "xy.z"; // lazy-minimal capture hit
                    } else if (i % 5 == 2) {
                      input = "no match here " + i; // miss: no x/y run followed by a dot
                    } else if (i % 5 == 3) {
                      input = (i % 2 == 0) ? "xy" : "xyq" + i; // miss: no dot at all
                    } else {
                      // miss: empty second capture / wrong first character
                      input = (i % 3 == 0) ? "xy./" : "z" + i + ".abc";
                    }
                    // A fresh runtime matcher per iteration — one matcher is never shared.
                    BitStateMatcher half =
                        hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
                    // Safe publication under contention: every fresh half must alias the ONE
                    // shared reject cache whose transitions the misses below populate lazily.
                    ok &= (field(half, "rejectDfa") == sharedReject);
                    // Execute on the BitState NFA half directly (not the hybrid DFA half).
                    MatchResult r = half.findMatch(input);
                    jm.reset(input);
                    boolean expected = jm.find();
                    ok &= (expected == (r != null));
                    if (expected) {
                      ok &= spansMatch(jm, r);
                    }
                  }
                  return ok;
                });
      }
      start.countDown();
      for (Future<Boolean> r : results) {
        assertTrue(
            r.get(60, TimeUnit.SECONDS),
            "no thread observed a wrong result, a wrong span, or an unshared reject cache");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Entry/bundle publication under contention: the cache is EMPTY when the latch releases, so the
   * first {@code RuntimeCompiler.compile} happens concurrently inside the workers — losing threads
   * may build equivalent bundles that must be discarded by {@code putIfAbsent}, and every
   * winner-published bundle must be seen fully initialized by all other threads. Each worker
   * records the identity of its half's shared setup; after the join, all workers must have observed
   * one and the same bundle (statesById identity) and reject cache.
   */
  @Test
  void concurrentEntryPublicationEstablishesOneSharedBundle() throws Exception {
    RuntimeCompiler.clearCache();
    int threads = Math.min(8, Math.max(4, Runtime.getRuntime().availableProcessors()));
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      @SuppressWarnings("unchecked")
      Future<Object[]>[] results = (Future<Object[]>[]) new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        results[t] =
            pool.submit(
                () -> {
                  start.await();
                  // First compile happens under contention: the cache is still empty here for
                  // every losing thread, and possibly for all of them.
                  BitStateMatcher half = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
                  return new Object[] {field(half, "statesById"), field(half, "rejectDfa"), half};
                });
      }
      start.countDown();
      Object[] first = results[0].get(60, TimeUnit.SECONDS);
      for (int t = 1; t < threads; t++) {
        Object[] obs = results[t].get(60, TimeUnit.SECONDS);
        assertSame(
            first[0],
            obs[0],
            "every worker must observe the ONE winner-published bundle (statesById identity)");
        assertSame(
            first[1],
            obs[1],
            "every worker must observe the ONE winner-published reject LazyDFACache");
        assertNotSame(first[2], obs[2], "matcher instances themselves must stay distinct");
      }
      // The published bundle is fully initialized, not a half-built constructor artifact.
      BitStateMatcher winner = hybridBitStateHalfOf(RuntimeCompiler.compile(GO_PATTERN));
      assertSame(first[0], field(winner, "statesById"));
      assertEquals(2, (int) intOf(winner, "groupCount"), "groupCount must be initialized");
      assertNotNull(field(winner, "greedyLoopMid"), "greedy-loop table must be initialized");
    } finally {
      pool.shutdownNow();
    }
  }
}
