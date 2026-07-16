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

import static org.objectweb.asm.Opcodes.*;

import com.datadoghq.reggie.ReggieFlags;
import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import com.datadoghq.reggie.codegen.analysis.BackreferencePatternInfo;
import com.datadoghq.reggie.codegen.analysis.CaptureProjection;
import com.datadoghq.reggie.codegen.analysis.ConcatGreedyGroupInfo;
import com.datadoghq.reggie.codegen.analysis.ConcatQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.FallbackPatternDetector;
import com.datadoghq.reggie.codegen.analysis.FixedRepetitionBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.GreedyBacktrackInfo;
import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo;
import com.datadoghq.reggie.codegen.analysis.LinearTokenSequencePlan;
import com.datadoghq.reggie.codegen.analysis.NestedQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.PatternCategorizer;
import com.datadoghq.reggie.codegen.analysis.PinnedBackreferenceInfo;
import com.datadoghq.reggie.codegen.analysis.QuantifiedGroupInfo;
import com.datadoghq.reggie.codegen.analysis.SpecializedOptionalGroupInfo;
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier;
import com.datadoghq.reggie.codegen.analysis.StructuralHash;
import com.datadoghq.reggie.codegen.analysis.VariableCaptureBackrefInfo;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.codegen.BackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.BitParallelGlushkovBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.BitStateBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.BoundedQuantifierBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.ConcatGreedyGroupBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.ConcatQuantifiedGroupsBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.CountingGlushkovBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.DFASwitchBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.DFATableBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.DFAUnrolledBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedRepetitionBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedSequenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedyBacktrackBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedyCharClassBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LazyDFABytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LinearPatternBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LiteralAlternationTrieGenerator;
import com.datadoghq.reggie.codegen.codegen.MultiGroupGreedyBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.NFABytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.NestedQuantifiedGroupsBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.OnePassBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.OptionalGroupBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.PinnedBackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.QuantifiedGroupBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.SpecializedOptionalGroupBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.StatelessLoopBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.VariableCaptureBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.PatternSyntaxException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Runtime compiler for regex patterns. Generates optimized bytecode at runtime and loads as hidden
 * classes (Java 21+). Thread-safe with two-level caching: - Level 1: Pattern string → matcher
 * instance (fast path) - Level 2: Structural hash → generated class (deduplication)
 */
public class RuntimeCompiler {

  private static final Logger LOG = Logger.getLogger(RuntimeCompiler.class.getName());

  // Level 1: Pattern string → matcher instance (fast path for exact matches).
  // NFA-backed patterns are NOT stored here; they use NFA_CLASS_CACHE instead.
  private static final ConcurrentHashMap<Object, ReggieMatcher> PATTERN_CACHE =
      new ConcurrentHashMap<>();

  /**
   * Factory for NFA-backed matchers. Encapsulates the compiled class and the name map so that each
   * compile() call can produce a fully-enriched fresh instance without re-parsing the pattern.
   */
  private static final class NfaMatcherFactory {
    final Class<? extends ReggieMatcher> matcherClass;
    final Map<String, Integer> nameMap;
    final boolean nativeRichApi;

    NfaMatcherFactory(
        Class<? extends ReggieMatcher> matcherClass,
        Map<String, Integer> nameMap,
        boolean nativeRichApi) {
      this.matcherClass = matcherClass;
      this.nameMap = nameMap;
      this.nativeRichApi = nativeRichApi;
    }

    ReggieMatcher newInstance(String pattern) {
      ReggieMatcher m = instantiateFromClassUnchecked(matcherClass, pattern);
      if (nativeRichApi) {
        m.markNativeRichApi();
      }
      if (!nameMap.isEmpty()) {
        m.setNameToIndex(nameMap);
        if (!m.embedsNameMap()) {
          m = new NameEnrichingMatcher(m);
        }
      }
      return m;
    }
  }

  // Level 1b: Pattern string → NFA matcher factory for NFA-backed strategies.
  // NFA matchers mutate shared instance fields (currentStates, nextStates, epsilonProcessed,
  // configGroupStarts) during matching, so a single shared instance cannot be used concurrently.
  // We cache a factory here that produces a fresh enriched instance on every compile() call.
  private static final ConcurrentHashMap<Object, NfaMatcherFactory> NFA_CLASS_CACHE =
      new ConcurrentHashMap<>();

  /**
   * Cached entry for PIKEVM_CAPTURE patterns: holds the immutable NFA and the compile-time name map
   * so that every compile() call can produce a correctly-enriched fresh PikeVMMatcher without
   * re-parsing the pattern.
   */
  private static final class PikeVMEntry {
    final NFA nfa;
    final Map<String, Integer> nameMap;

    PikeVMEntry(NFA nfa, Map<String, Integer> nameMap) {
      this.nfa = nfa;
      this.nameMap = nameMap;
    }

    ReggieMatcher newMatcher(String pattern) {
      ReggieMatcher m = new PikeVMMatcher(nfa, pattern);
      if (!nameMap.isEmpty()) {
        m.setNameToIndex(nameMap);
        if (!m.embedsNameMap()) {
          m = new NameEnrichingMatcher(m);
        }
      }
      return m;
    }
  }

  // Level 1c: Pattern string → PikeVMEntry for PIKEVM_CAPTURE patterns.
  // PikeVMMatcher carries mutable per-call buffers and must be freshly instantiated on every
  // compile() call; only the NFA (immutable after construction) and the name map are cached.
  private static final ConcurrentHashMap<Object, PikeVMEntry> PIKEVM_NFA_CACHE =
      new ConcurrentHashMap<>();

  /**
   * Cached entry for BITSTATE_CAPTURE patterns: holds the immutable NFA and the compile-time name
   * map so that every compile() call can produce a correctly-enriched fresh {@code BitStateMatcher}
   * without re-parsing the pattern. Parallel to {@link PikeVMEntry} rather than a generalization of
   * it (see doc/2026-07-03-bitstate-capture-engine-design.md, P1 decision: smaller diff, zero risk
   * to existing PikeVM caching).
   */
  private static final class BitStateEntry {
    final NFA nfa;
    final Map<String, Integer> nameMap;
    final boolean usePosixLastMatch;

    BitStateEntry(NFA nfa, Map<String, Integer> nameMap, boolean usePosixLastMatch) {
      this.nfa = nfa;
      this.nameMap = nameMap;
      this.usePosixLastMatch = usePosixLastMatch;
    }

    ReggieMatcher newMatcher(String pattern) {
      // Phase 2 Task 2.1 (Option A, doc/2026-07-10-tdfa-capture-engine-impl-plan.md §4): try the
      // TDFA capture engine before BitStateMatcher's own BUDGET_CELLS-overflow call falls all the
      // way through to PikeVMMatcher. null when LaurikariEligibility rejects this pattern.
      ReggieMatcher laurikari =
          LaurikariDfaSupport.tryCreate(nfa, pattern, nfa.getGroupCount(), usePosixLastMatch);
      ReggieMatcher m = new BitStateMatcher(nfa, pattern, laurikari);
      if (!nameMap.isEmpty()) {
        if (laurikari != null) {
          laurikari.setNameToIndex(nameMap);
        }
        m.setNameToIndex(nameMap);
        if (!m.embedsNameMap()) {
          m = new NameEnrichingMatcher(m);
        }
      }
      return m;
    }
  }

  // Level 1d: Pattern string → BitStateEntry for BITSTATE_CAPTURE patterns.
  // BitStateMatcher carries mutable per-call buffers and must be freshly instantiated on every
  // compile() call; only the NFA (immutable after construction) and the name map are cached.
  private static final ConcurrentHashMap<Object, BitStateEntry> BITSTATE_NFA_CACHE =
      new ConcurrentHashMap<>();

  // Level 2: Structural hash → generated class (deduplication for similar patterns).
  // Key is Long (64-bit) to make birthday collisions essentially impossible across large pattern
  // sets; an int key was observed to cause structural-cache false-hits with wrong match semantics.
  // Each entry also carries an INDEPENDENT verification hash so a hit can be confirmed before reuse
  // (verify-on-hit): if the verification differs, the 64-bit key collided across structurally
  // distinct patterns, and we regenerate the correct class rather than return a wrong matcher.
  private static final ConcurrentHashMap<Long, CachedStructure> STRUCTURE_CACHE =
      new ConcurrentHashMap<>();

  /** A cached generated class plus the independent verification hash of its structure. */
  private static final class CachedStructure {
    final Class<? extends ReggieMatcher> clazz;
    final long verify;

    CachedStructure(Class<? extends ReggieMatcher> clazz, long verify) {
      this.clazz = clazz;
      this.verify = verify;
    }
  }

  // Fires once if a structural-cache key collision is ever detected at runtime (verify mismatch),
  // so the (astronomically rare) event is observable rather than silent.
  private static final java.util.concurrent.atomic.AtomicBoolean STRUCT_COLLISION_WARNED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  // Lookup for hidden class definition (Java 21+)
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  /**
   * Compile pattern with automatic cache key (pattern string). Thread-safe: uses computeIfAbsent to
   * avoid duplicate compilation.
   *
   * @param pattern the regex pattern string
   * @return compiled matcher
   * @throws PatternSyntaxException if pattern is invalid
   */
  public static ReggieMatcher compile(String pattern) {
    return compile(pattern, ReggieOptions.DEFAULT);
  }

  /** Compile a pattern with supported {@link ReggieFlags}. */
  public static ReggieMatcher compile(String pattern, int flags) {
    return compile(pattern, flags, ReggieOptions.DEFAULT);
  }

  /** Compile a pattern with supported {@link ReggieFlags} and engine compilation options. */
  public static ReggieMatcher compile(String pattern, int flags, ReggieOptions options) {
    if (flags == 0) {
      return compile(pattern, options);
    }
    String normalizedPattern = normalizePatternFlags(pattern, flags);
    return compile(normalizedPattern, options, cacheKeyForFlags(pattern, flags, options), pattern);
  }

  /** Compile pattern with runtime compilation options. */
  public static ReggieMatcher compile(String pattern, ReggieOptions options) {
    return compile(pattern, options, cacheKeyFor(pattern, options), pattern);
  }

  private static ReggieMatcher compile(
      String pattern, ReggieOptions options, Object cacheKey, String reportedPattern) {

    // Fast path: PIKEVM_CAPTURE patterns are in PIKEVM_NFA_CACHE — return a fresh matcher.
    // PikeVMMatcher carries mutable per-call buffers and must not be shared across calls.
    PikeVMEntry pikevmEntry = PIKEVM_NFA_CACHE.get(cacheKey);
    if (pikevmEntry != null) {
      return reportPattern(pikevmEntry.newMatcher(pattern), reportedPattern);
    }

    // Fast path: BITSTATE_CAPTURE patterns are in BITSTATE_NFA_CACHE — return a fresh matcher.
    // BitStateMatcher carries mutable per-call buffers and must not be shared across calls.
    BitStateEntry bitStateEntry = BITSTATE_NFA_CACHE.get(cacheKey);
    if (bitStateEntry != null) {
      return reportPattern(bitStateEntry.newMatcher(pattern), reportedPattern);
    }

    // Fast path: NFA-backed patterns are in NFA_CLASS_CACHE — return a fresh instance.
    // NFA matchers mutate shared instance fields during matching and cannot be shared across
    // threads or calls; we cache a factory and instantiate per-call instead.
    NfaMatcherFactory factory = NFA_CLASS_CACHE.get(cacheKey);
    if (factory != null) {
      return reportPattern(factory.newInstance(pattern), reportedPattern);
    }

    // Slow path: compile and cache the result.
    // compileInternal will populate NFA_CLASS_CACHE if the strategy is NFA-backed, in which case
    // the L1 entry is immediately removed so that subsequent calls hit the fast path above.
    // The reported pattern is set inside the mapping function so the instance published to
    // PATTERN_CACHE is already fully initialized and is never mutated again after publication.
    ReggieMatcher compiled =
        PATTERN_CACHE.computeIfAbsent(
            cacheKey, k -> reportPattern(compileInternal(pattern, options, k), reportedPattern));

    // Post-compilation fixup: if compileInternal registered this pattern as PIKEVM_CAPTURE,
    // remove it from L1 and return a fresh matcher so callers never share mutable state.
    pikevmEntry = PIKEVM_NFA_CACHE.get(cacheKey);
    if (pikevmEntry != null) {
      PATTERN_CACHE.remove(cacheKey, compiled);
      return reportPattern(pikevmEntry.newMatcher(pattern), reportedPattern);
    }

    // Post-compilation fixup: if compileInternal registered this pattern as BITSTATE_CAPTURE,
    // remove it from L1 and return a fresh matcher so callers never share mutable state.
    bitStateEntry = BITSTATE_NFA_CACHE.get(cacheKey);
    if (bitStateEntry != null) {
      PATTERN_CACHE.remove(cacheKey, compiled);
      return reportPattern(bitStateEntry.newMatcher(pattern), reportedPattern);
    }

    // Post-compilation fixup: if compileInternal registered this pattern as NFA-backed,
    // remove it from L1 and return a fresh instance so callers never share NFA state.
    factory = NFA_CLASS_CACHE.get(cacheKey);
    if (factory != null) {
      PATTERN_CACHE.remove(cacheKey, compiled);
      return reportPattern(factory.newInstance(pattern), reportedPattern);
    }
    return compiled;
  }

  private static ReggieMatcher reportPattern(ReggieMatcher matcher, String pattern) {
    matcher.setReportedPattern(pattern);
    return matcher;
  }

  private static final char NAME_SEP = ''; // US (unit separator)
  private static final char PAIR_SEP = ''; // RS (record separator)

  /** Encodes a group-name-to-index map into a compact string for baking into a delegating stub. */
  public static String encodeNameMap(Map<String, Integer> nameMap) {
    if (nameMap == null || nameMap.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> e : nameMap.entrySet()) {
      if (sb.length() > 0) {
        sb.append(PAIR_SEP);
      }
      sb.append(e.getKey()).append(NAME_SEP).append(e.getValue());
    }
    return sb.toString();
  }

  /** Inverse of {@link #encodeNameMap}. Returns an empty map for an empty string. */
  public static Map<String, Integer> decodeNameMap(String encoded) {
    if (encoded == null || encoded.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    Map<String, Integer> m = new java.util.LinkedHashMap<>();
    int i = 0;
    while (i < encoded.length()) {
      int pairEnd = encoded.indexOf(PAIR_SEP, i);
      if (pairEnd < 0) {
        pairEnd = encoded.length();
      }
      int sep = encoded.indexOf(NAME_SEP, i);
      String name = encoded.substring(i, sep);
      int idx = Integer.parseInt(encoded.substring(sep + 1, pairEnd));
      m.put(name, idx);
      i = pairEnd + 1;
    }
    return m;
  }

  /**
   * Compile a pattern that the annotation processor resolved to {@code PIKEVM_CAPTURE}, skipping
   * strategy re-analysis. The NFA is still built by the canonical runtime builder; only the routing
   * decision and name map are carried from compile time. Used by generated delegating stubs.
   */
  public static ReggieMatcher compilePikeVm(String pattern, String encodedNames) {
    PikeVMEntry entry = PIKEVM_NFA_CACHE.get(pattern);
    if (entry != null) {
      return entry.newMatcher(pattern);
    }
    try {
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);
      String pikeVmFallbackReason =
          FallbackPatternDetector.needsFallback(
              ast, PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
      if (pikeVmFallbackReason != null) {
        throw new UnsupportedPatternException(
            "PIKEVM_CAPTURE pattern is unsafe: " + pikeVmFallbackReason + " in /" + pattern + "/");
      }
      Map<String, Integer> nameMap = decodeNameMap(encodedNames);
      int groupCount = countGroups(pattern);
      NFA nfa = new ThompsonBuilder().build(ast, groupCount);
      PIKEVM_NFA_CACHE.putIfAbsent(pattern, new PikeVMEntry(nfa, nameMap));
      return PIKEVM_NFA_CACHE.get(pattern).newMatcher(pattern);
    } catch (RegexParser.ParseException e) {
      throw new java.util.regex.PatternSyntaxException(e.getMessage(), pattern, -1);
    }
  }

  /**
   * Compile with explicit cache key (for user-controlled caching).
   *
   * @param key custom cache key
   * @param pattern the regex pattern string
   * @return compiled matcher
   * @throws PatternSyntaxException if pattern is invalid
   */
  public static ReggieMatcher cached(String key, String pattern) {
    ReggieMatcher existing = PATTERN_CACHE.get(key);
    if (existing != null) {
      if (!pattern.equals(existing.pattern())) {
        throw new IllegalStateException(
            "Cache key '" + key + "' is already mapped to a different pattern");
      }
      return existing;
    }
    return PATTERN_CACHE.computeIfAbsent(key, k -> compileInternal(pattern));
  }

  /** Compile with explicit cache key and runtime compilation options. */
  public static ReggieMatcher cached(String key, String pattern, ReggieOptions options) {
    ReggieMatcher existing = PATTERN_CACHE.get(key);
    if (existing != null) {
      if (!pattern.equals(existing.pattern())) {
        throw new IllegalStateException(
            "Cache key '" + key + "' is already mapped to a different pattern");
      }
      return existing;
    }
    return PATTERN_CACHE.computeIfAbsent(key, k -> compileInternal(pattern, options));
  }

  /**
   * Clears all three caches: {@code PATTERN_CACHE} (matcher instances), {@code NFA_CLASS_CACHE}
   * (NFA-backed compiled classes), and {@code STRUCTURE_CACHE} (all hidden Class objects). Clearing
   * the structure cache releases hidden-class references, allowing the JVM to reclaim the metaspace
   * they occupy. For workloads compiling many unique user-provided patterns, call this periodically
   * to avoid unbounded metaspace growth.
   */
  public static void clearCache() {
    PATTERN_CACHE.clear();
    NFA_CLASS_CACHE.clear();
    PIKEVM_NFA_CACHE.clear();
    BITSTATE_NFA_CACHE.clear();
    STRUCTURE_CACHE.clear();
  }

  /** Get current pattern cache size (level 1). */
  public static int cacheSize() {
    return PATTERN_CACHE.size();
  }

  /**
   * Get structural cache size (level 2). This represents the number of unique bytecode structures
   * generated.
   */
  public static int structuralCacheSize() {
    return STRUCTURE_CACHE.size();
  }

  /**
   * Get a string snapshot of all level-1 cache keys. Plain pattern-string keys (from {@code
   * compile(pattern[, options])}) are returned verbatim. Keys for patterns compiled with {@link
   * ReggieFlags} (via {@code compile(pattern, flags[, options])}) are backed by an internal {@code
   * FlaggedCacheKey} record rather than the pattern string itself; those entries are rendered as
   * {@code "<pattern> [flags=<flags>]"}, or {@code "<pattern> [flags=<flags> options=<options>]"}
   * when non-default {@link ReggieOptions} were also used, so flagged compilations remain visible
   * for cache-size accounting and debugging, and entries that differ only by options no longer
   * collapse to the same displayed string.
   */
  public static Set<String> cachedPatterns() {
    return PATTERN_CACHE.keySet().stream()
        .map(RuntimeCompiler::cacheKeyToDisplayString)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static String cacheKeyToDisplayString(Object key) {
    if (key instanceof String s) {
      return s;
    }
    if (key instanceof FlaggedCacheKey flaggedKey) {
      String optionsSuffix = "";
      if (!flaggedKey.optionsKey().equals(flaggedKey.pattern())) {
        // cacheKeyFor() appends "\0<OPTION_NAME>" for each enabled ReggieOption; render it as a
        // comma-separated, human-readable options=... suffix so distinct ReggieOptions combined
        // with the same pattern+flags no longer collapse to the same displayed string.
        String encodedOptions = flaggedKey.optionsKey().substring(flaggedKey.pattern().length());
        optionsSuffix = " options=" + encodedOptions.substring(1).replace('\0', ',');
      }
      return flaggedKey.pattern() + " [flags=" + flaggedKey.flags() + optionsSuffix + "]";
    }
    return String.valueOf(key);
  }

  /**
   * Internal compilation: pattern → AST → NFA → strategy → bytecode/class → instance. Uses
   * two-level caching: - Pattern string cache (level 1) already checked by compile() - Structural
   * cache (level 2) checked here
   */
  private static ReggieMatcher compileInternal(String pattern) {
    return compileInternal(pattern, ReggieOptions.DEFAULT, pattern);
  }

  private static ReggieMatcher compileInternal(String pattern, ReggieOptions options) {
    return compileInternal(pattern, options, pattern);
  }

  private static String cacheKeyFor(String pattern, ReggieOptions options) {
    StringBuilder sb = null;
    for (ReggieOption o : ReggieOption.values()) {
      if (options.has(o)) {
        if (sb == null) {
          sb = new StringBuilder(pattern);
        }
        sb.append('\0').append(o.name());
      }
    }
    return sb == null ? pattern : sb.toString();
  }

  private record FlaggedCacheKey(String pattern, int flags, String optionsKey) {}

  private static FlaggedCacheKey cacheKeyForFlags(
      String pattern, int flags, ReggieOptions options) {
    return new FlaggedCacheKey(pattern, flags, cacheKeyFor(pattern, options));
  }

  private static String normalizePatternFlags(String pattern, int flags) {
    if (!ReggieFlags.areSupported(flags)) {
      throw new IllegalArgumentException("Unsupported Reggie regex flags: " + flags);
    }

    StringBuilder normalized = new StringBuilder(pattern.length() + 12);
    // CASE_INSENSITIVE is emitted first so isCaseInsensitive() retains its existing routing hint.
    if ((flags & ReggieFlags.CASE_INSENSITIVE) != 0) {
      normalized.append("(?i)");
    }
    if ((flags & ReggieFlags.MULTILINE) != 0) {
      normalized.append("(?m)");
    }
    if ((flags & ReggieFlags.DOTALL) != 0) {
      normalized.append("(?s)");
    }
    normalized.append(
        (flags & ReggieFlags.LITERAL) != 0 ? java.util.regex.Pattern.quote(pattern) : pattern);
    return normalized.toString();
  }

  private static ReggieMatcher fallbackOrThrow(
      String pattern, String reason, Map<String, Integer> nameMap, ReggieOptions options) {
    if (!options.has(ReggieOption.ALLOW_JDK_FALLBACK)) {
      throw new UnsupportedPatternException(reason);
    }
    ReggieMatcher fallback = new JavaRegexFallbackMatcher(pattern, reason);
    if (nameMap != null && !nameMap.isEmpty()) {
      fallback.setNameToIndex(nameMap);
    }
    return fallback;
  }

  /**
   * Compile a pattern with an explicit L1 cache key. When the strategy is NFA-backed, the compiled
   * class is stored in NFA_CLASS_CACHE under {@code cacheKey} so that compile() can skip L1 and
   * return a fresh instance on every subsequent call.
   */
  private static ReggieMatcher compileInternal(
      String pattern, ReggieOptions options, Object cacheKey) {
    try {
      // 1. Parse pattern to AST
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);
      Map<String, Integer> nameMap = parser.getGroupNameMap();
      if (options.has(ReggieOption.CAPTURE_NAMED_ONLY)) {
        ast = CaptureProjection.preserveNamedAndSemanticCaptures(ast);
        ReggieMatcher linearTokenSequenceMatcher =
            tryCompileLinearTokenSequence(pattern, ast, nameMap);
        if (linearTokenSequenceMatcher != null) {
          return linearTokenSequenceMatcher;
        }
      }

      // 2. Check if pattern requires recursive descent (context-free features)
      // Do this early to avoid unnecessary NFA building
      int groupCount = countGroups(pattern);
      boolean caseInsensitive = isCaseInsensitive(pattern);
      NFA nfa;
      if (PatternAnalyzer.requiresRecursiveDescent(ast)) {
        // Pattern uses context-free features (subroutines, conditionals, etc.)
        // Skip NFA building - not needed for recursive descent strategy
        nfa = null;
      } else {
        // Build NFA for regular patterns
        ThompsonBuilder nfaBuilder = new ThompsonBuilder();
        nfa = nfaBuilder.build(ast, groupCount);
      }

      // 3. Analyze and select strategy
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
      PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();

      // 3.5. Fall back to java.util.regex for DFA anchor-condition dilution not covered by
      // explicit misplaced-anchor or string-end-anchor checks: OPTIMIZED_NFA may produce wrong
      // results for these patterns (e.g. dot matching newline, group-span bugs).
      // PIKEVM_CAPTURE evaluates anchors correctly at every search position; anchorConditionDiluted
      // on a PIKEVM result is only used by the hybrid pre-check (§4 below) to skip the DFA pass.
      // BITSTATE_CAPTURE shares this guard: it is an NFA interpreter with identical anchor
      // semantics to PIKEVM_CAPTURE (same NFA, same checkAnchor logic), just a different traversal
      // strategy over it.
      if (result.anchorConditionDiluted
          && result.strategy != PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE
          && result.strategy != PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE) {
        return fallbackOrThrow(
            pattern, "anchor condition diluted in DFA construction", nameMap, options);
      }
      if (result.alternationPriorityConflict) {
        return fallbackOrThrow(
            pattern,
            "alternation priority conflict: DFA longest-match vs NFA first-alternative",
            nameMap,
            options);
      }
      // A6 [NEEDS-RND]: captureAmbiguous=true means the NFA has a bypass path around at least one
      // capturing group (reachable accept state without entering that group's enterGroup marker).
      // Native backref strategies cannot resolve which thread's binding wins in that case.
      // Fix requires per-state group arrays (issue #38 Cat B). See BackrefEngineGapsTest.a6.
      if (result.captureAmbiguous) {
        return fallbackOrThrow(
            pattern,
            "capture-ambiguous group bindings: group spans require java.util.regex semantics",
            nameMap,
            options);
      }

      // 3.6. PIKEVM_CAPTURE: cache the NFA + name map so every compile() call produces a fresh,
      // correctly-enriched PikeVMMatcher without re-parsing the pattern.
      // B16 guard: nullable group content under a nullable outer quantifier diverges even in PikeVM
      // (wrong last-iteration spans). This must be checked before the early return so patterns
      // arriving via the StateExplosionException path still fall back to JDK.
      if (result.strategy == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE) {
        String pikeVmFallbackReason = FallbackPatternDetector.needsFallback(ast, result.strategy);
        if (pikeVmFallbackReason != null) {
          return fallbackOrThrow(pattern, pikeVmFallbackReason, nameMap, options);
        }
        NFA pikeVmNfa = result.lazyNfa ? new ThompsonBuilder(true).build(ast, groupCount) : nfa;
        PIKEVM_NFA_CACHE.putIfAbsent(cacheKey, new PikeVMEntry(pikeVmNfa, nameMap));
        return PIKEVM_NFA_CACHE.get(cacheKey).newMatcher(pattern);
      }

      // 3.7. BITSTATE_CAPTURE: same caching shape as 3.6 above, but into BITSTATE_NFA_CACHE and a
      // BitStateMatcher. PatternAnalyzer only ever recommends BITSTATE_CAPTURE for patterns that
      // would otherwise have been PIKEVM_CAPTURE (see PatternAnalyzer#routeBitState), so it needs
      // the same FallbackPatternDetector guard.
      if (result.strategy == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE) {
        String bitStateFallbackReason = FallbackPatternDetector.needsFallback(ast, result.strategy);
        if (bitStateFallbackReason != null) {
          return fallbackOrThrow(pattern, bitStateFallbackReason, nameMap, options);
        }
        NFA bitStateNfa = result.lazyNfa ? new ThompsonBuilder(true).build(ast, groupCount) : nfa;
        BITSTATE_NFA_CACHE.putIfAbsent(
            cacheKey, new BitStateEntry(bitStateNfa, nameMap, result.usePosixLastMatch));
        return BITSTATE_NFA_CACHE.get(cacheKey).newMatcher(pattern);
      }

      String fallbackReason = FallbackPatternDetector.needsFallback(ast, result.strategy);
      if (fallbackReason != null) {
        return fallbackOrThrow(pattern, fallbackReason, nameMap, options);
      }

      // 4. Check if we should use hybrid mode (DFA + NFA for groups)
      if (groupCount > 0 && shouldUseHybrid(result)) {
        PatternAnalyzer.MatchingStrategyResult dfaResult = analyzer.analyzeAndRecommend(true);
        // Skip hybrid when the anchor-free DFA is anchor-diluted: the DFA incorrectly models
        // anchor conditions so it cannot serve as the fast-matching pass. compileHybrid handles
        // dfaResult.dfa==null by generating a pure NFA matcher, so non-diluted PIKEVM results
        // (e.g. from hasCapturingGroupInQuantifiedSection) still reach the NFA fallback inside.
        if (!dfaResult.anchorConditionDiluted) {
          ReggieMatcher hybrid =
              compileHybrid(pattern, ast, nfa, dfaResult, result, caseInsensitive, options);
          hybrid.setNameToIndex(nameMap);
          return hybrid;
        }
        // Hybrid DFA anchor-diluted: skip hybrid, fall through to NFA-only routing below.
      }

      // 5. Compute structural hash (64-bit cache key) + an independent verification hash.
      long structHash;
      long verifyHash;
      if (nfa != null) {
        structHash = StructuralHash.compute(result, nfa, caseInsensitive);
        verifyHash = StructuralHash.computeVerification(result, nfa, caseInsensitive);
      } else {
        structHash = StructuralHash.computeWithoutGroupCount(result);
        verifyHash = StructuralHash.computeVerificationWithoutGroupCount(result);
      }

      // 6. Check structural cache (level 2), verifying the hit before trusting it.
      CachedStructure cached = STRUCTURE_CACHE.get(structHash);
      Class<? extends ReggieMatcher> matcherClass = null;
      boolean cacheable = true;
      if (cached != null) {
        if (cached.verify == verifyHash) {
          matcherClass = cached.clazz; // verified structural match — safe to reuse
        } else {
          // 64-bit key collision across structurally distinct patterns (~astronomically rare).
          // Do NOT reuse the wrong class and do NOT evict the legitimate entry: regenerate the
          // correct class for this pattern (the per-pattern L1 caches will hold it). This converts
          // a would-be silent wrong answer into a correct (re-generated) result.
          cacheable = false;
          if (STRUCT_COLLISION_WARNED.compareAndSet(false, true)) {
            LOG.warning(
                "Reggie structural-cache key collision detected (verify-on-hit) for pattern '"
                    + pattern
                    + "'; regenerating to avoid a wrong matcher. This is expected to be vanishingly"
                    + " rare; if it recurs, StructuralHash needs more discriminating fields.");
          }
        }
      }

      if (matcherClass == null) {
        // 7. Cache miss (or collision): Generate bytecode and define hidden class
        byte[] bytecode = generateBytecode(pattern, result, nfa, ast, caseInsensitive);

        MethodHandles.Lookup hiddenLookup =
            LOOKUP.defineHiddenClass(
                bytecode,
                true, // initialize immediately
                MethodHandles.Lookup.ClassOption.NESTMATE);

        matcherClass = hiddenLookup.lookupClass().asSubclass(ReggieMatcher.class);

        // 8. Cache for future structurally-identical patterns (skip on a detected key collision).
        if (cacheable) {
          STRUCTURE_CACHE.put(structHash, new CachedStructure(matcherClass, verifyHash));
        }
      }

      // 8b. For NFA-backed strategies: register a factory in NFA_CLASS_CACHE so that compile()
      // can skip L1 and always return a fresh per-call instance. NFA matchers mutate shared
      // fields (currentStates, nextStates, …) during matching and are not safe to share across
      // threads or calls. The factory also captures the name map so that each fresh instance is
      // fully enriched without re-parsing the pattern.
      boolean nativeRichApi =
          StrategyJdkClassifier.classifyJdkDependency(result.strategy)
              == StrategyJdkClassifier.StrategyJdkClass.NATIVE;
      if (isNfaBacked(result.strategy)) {
        NFA_CLASS_CACHE.putIfAbsent(
            cacheKey, new NfaMatcherFactory(matcherClass, nameMap, nativeRichApi));
        // Return an enriched instance for this (first) call.
        return NFA_CLASS_CACHE.get(cacheKey).newInstance(pattern);
      }

      ReggieMatcher matcher = instantiateFromClass(matcherClass, pattern);
      if (nativeRichApi) {
        matcher.markNativeRichApi();
      }

      // 9. Inject name map and wrap for named group support
      if (!nameMap.isEmpty()) {
        matcher.setNameToIndex(nameMap);
        if (!matcher.embedsNameMap()) {
          matcher = new NameEnrichingMatcher(matcher);
        }
      }

      return matcher;

    } catch (org.objectweb.asm.MethodTooLargeException e) {
      // Very large patterns can exceed the JVM 64 KB per-method limit. DFASwitchBytecodeGenerator
      // mitigates this via STATE_SPLIT_THRESHOLD bucket-helper splitting; hitting this path for a
      // DFA_SWITCH* strategy indicates a STATE_SPLIT_THRESHOLD bug in DFASwitchBytecodeGenerator.
      // Other generators (e.g. NFABytecodeGenerator) do not yet implement splitting and may still
      // reach this path for extremely large alternations. Preserve drop-in behavior by falling back
      // to java.util.regex, and include the generated method and bytecode size in the warning so
      // the responsible generator can be identified and fixed.
      return fallbackOrThrow(
          pattern,
          "generated method too large: "
              + e.getClassName()
              + "."
              + e.getMethodName()
              + e.getDescriptor()
              + " codeSize="
              + e.getCodeSize(),
          null,
          options);
    } catch (RegexParser.UnsupportedPatternException | UnsupportedOperationException e) {
      throw new UnsupportedPatternException(
          "Unsupported regex pattern: " + pattern + ": " + e.getMessage(), e);
    } catch (UnsupportedPatternException e) {
      throw e;
    } catch (RegexParser.ParseException e) {
      // Parser reported a structural error — expose as PatternSyntaxException so callers
      // receive a typed, documented exception rather than a generic RuntimeException.
      throw new PatternSyntaxException(e.getMessage(), pattern, -1);
    } catch (PatternSyntaxException e) {
      // Re-throw PatternSyntaxException as-is
      throw e;
    } catch (Exception e) {
      // Wrap other exceptions
      throw new RuntimeException("Failed to compile pattern: " + pattern, e);
    }
  }

  private static ReggieMatcher tryCompileLinearTokenSequence(
      String pattern, RegexNode ast, Map<String, Integer> nameMap) {
    return LinearTokenSequencePlan.from(PatternCategorizer.categorize(ast))
        .filter(RuntimeCompiler::isRuntimeExecutableLinearTokenSequence)
        .map(plan -> new LinearTokenSequenceMatcher(pattern, plan, countGroups(pattern), nameMap))
        .map(m -> m.embedsNameMap() ? m : new NameEnrichingMatcher(m))
        .orElse(null);
  }

  private static boolean isRuntimeExecutableLinearTokenSequence(LinearTokenSequencePlan plan) {
    for (int i = 0; i < plan.ops().size(); i++) {
      LinearTokenSequencePlan.Op op = plan.ops().get(i);
      if (op.kind() == LinearTokenSequencePlan.OpKind.ANCHOR) return false;
      if (op.kind() == LinearTokenSequencePlan.OpKind.SKIP_ANY && i != plan.ops().size() - 1) {
        return false;
      }
      if (op.kind() == LinearTokenSequencePlan.OpKind.OPTIONAL_SEQUENCE
          && i + 1 < plan.ops().size()
          && canOptionalPresentBranchStealFollowingInput(op, plan.ops().get(i + 1))) {
        return false;
      }
    }
    return true;
  }

  private static boolean canOptionalPresentBranchStealFollowingInput(
      LinearTokenSequencePlan.Op optional, LinearTokenSequencePlan.Op next) {
    if (optional.children().isEmpty()) return false;
    LinearTokenSequencePlan.Op first = optional.children().get(0);
    if (first.kind() == LinearTokenSequencePlan.OpKind.LITERAL
        && next.kind() == LinearTokenSequencePlan.OpKind.LITERAL) {
      return !first.literal().isEmpty()
          && !next.literal().isEmpty()
          && first.literal().charAt(0) == next.literal().charAt(0);
    }
    return false;
  }

  /**
   * Check if the strategy would benefit from hybrid mode. Hybrid mode uses DFA for fast matching
   * and NFA for group extraction.
   */
  private static boolean shouldUseHybrid(PatternAnalyzer.MatchingStrategyResult result) {
    // Hybrid is only useful when we'd normally use NFA just because of groups
    // If we're already using NFA for other reasons (backrefs, state explosion),
    // there's no benefit from hybrid
    if (result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA) {
      return true;
    }
    // Also use hybrid for DFA strategies that need POSIX last-match semantics
    // DFA can't track groups correctly in quantified contexts, so we use NFA
    if (result.usePosixLastMatch) {
      return true;
    }
    return false;
  }

  /** Compile a hybrid matcher: DFA for fast matching, NFA for group extraction. */
  private static ReggieMatcher compileHybrid(
      String pattern,
      RegexNode ast,
      NFA nfa,
      PatternAnalyzer.MatchingStrategyResult dfaResult,
      PatternAnalyzer.MatchingStrategyResult originalResult,
      boolean caseInsensitive,
      ReggieOptions options)
      throws Exception {
    // dfaResult is pre-computed by compileInternal; anchor-diluted patterns are pre-filtered.
    // When dfaResult.dfa==null but originalResult.dfa!=null, use original DFA for booleans + NFA.
    if (dfaResult.dfa == null) {
      if (originalResult.dfa != null) {
        // Use the original DFA for boolean matching, NFA for group extraction.
        byte[] dfaBytecode = generateBytecode(pattern, originalResult, nfa, ast, caseInsensitive);
        ReggieMatcher dfaMatcher = instantiateMatcher(dfaBytecode, pattern);
        PatternAnalyzer.MatchingStrategyResult nfaResult =
            new PatternAnalyzer.MatchingStrategyResult(
                PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA,
                null,
                null,
                false,
                originalResult.requiredLiterals,
                originalResult.lookaheadGreedyInfo,
                originalResult.usePosixLastMatch);
        byte[] nfaBytecode = generateBytecode(pattern, nfaResult, nfa, ast, caseInsensitive);
        ReggieMatcher nfaMatcher = instantiateMatcher(nfaBytecode, pattern);
        return new HybridMatcher(pattern, dfaMatcher, nfaMatcher);
      }
      // No DFA available: fall back to pure NFA
      PatternAnalyzer.MatchingStrategyResult nfaResult =
          new PatternAnalyzer.MatchingStrategyResult(
              PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA,
              null,
              null,
              false,
              originalResult.requiredLiterals,
              originalResult.lookaheadGreedyInfo,
              originalResult.usePosixLastMatch);
      byte[] bytecode = generateBytecode(pattern, nfaResult, nfa, ast, caseInsensitive);
      return instantiateMatcher(bytecode, pattern);
    }

    // 2. Generate DFA matcher (for fast matching)
    byte[] dfaBytecode = generateBytecode(pattern, dfaResult, nfa, ast, caseInsensitive);
    ReggieMatcher dfaMatcher = instantiateMatcher(dfaBytecode, pattern);

    // 3. Generate NFA matcher (for group extraction) - preserve POSIX flag!
    PatternAnalyzer.MatchingStrategyResult nfaResult =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA,
            null,
            null,
            false,
            originalResult.requiredLiterals,
            originalResult.lookaheadGreedyInfo,
            originalResult.usePosixLastMatch);
    byte[] nfaBytecode = generateBytecode(pattern, nfaResult, nfa, ast, caseInsensitive);
    ReggieMatcher nfaMatcher = instantiateMatcher(nfaBytecode, pattern);

    // 4. Return hybrid matcher
    return new HybridMatcher(pattern, dfaMatcher, nfaMatcher);
  }

  /** Instantiate a matcher from bytecode. The pattern string is passed to the constructor. */
  private static ReggieMatcher instantiateMatcher(byte[] bytecode, String pattern)
      throws Exception {
    // Debug: Save bytecode before loading if REGGIE_DEBUG_BYTECODE env var is set
    String debugDir = System.getenv("REGGIE_DEBUG_BYTECODE");
    if (debugDir != null) {
      try {
        java.nio.file.Path dir = java.nio.file.Paths.get(debugDir);
        java.nio.file.Files.createDirectories(dir);
        String safePattern =
            pattern.replaceAll("[^a-zA-Z0-9]", "_").substring(0, Math.min(50, pattern.length()));
        java.nio.file.Path classFile = dir.resolve("ReggieMatcher_" + safePattern + ".class");
        java.nio.file.Files.write(classFile, bytecode);
        System.err.println("DEBUG: Saved bytecode to " + classFile);
      } catch (Exception e) {
        System.err.println("DEBUG: Failed to save bytecode: " + e.getMessage());
      }
    }

    MethodHandles.Lookup hiddenLookup =
        LOOKUP.defineHiddenClass(bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE);
    Class<?> hiddenClass = hiddenLookup.lookupClass();
    Constructor<?> ctor = hiddenClass.getDeclaredConstructor(String.class);
    return (ReggieMatcher) ctor.newInstance(pattern);
  }

  /**
   * Instantiate a matcher from an already-loaded class. Used for structural cache hits. Note: The
   * class must have a constructor that accepts a String pattern parameter.
   */
  private static ReggieMatcher instantiateFromClass(
      Class<? extends ReggieMatcher> matcherClass, String pattern) throws Exception {
    Constructor<? extends ReggieMatcher> ctor = matcherClass.getDeclaredConstructor(String.class);
    return ctor.newInstance(pattern);
  }

  /**
   * Instantiate a matcher from a compiled class, wrapping checked exceptions as runtime exceptions.
   * Used on the fast path in compile() where the class is already known to be valid.
   */
  private static ReggieMatcher instantiateFromClassUnchecked(
      Class<? extends ReggieMatcher> matcherClass, String pattern) {
    try {
      Constructor<? extends ReggieMatcher> ctor = matcherClass.getDeclaredConstructor(String.class);
      return ctor.newInstance(pattern);
    } catch (Exception e) {
      throw new RuntimeException("Failed to instantiate matcher for pattern: " + pattern, e);
    }
  }

  /**
   * Returns true for matching strategies whose generated bytecode reads and writes shared instance
   * fields (currentStates, nextStates, epsilonProcessed, configGroupStarts) during matching.
   * Instances of these matchers must not be shared across threads or sequential compile() calls.
   */
  private static boolean isNfaBacked(PatternAnalyzer.MatchingStrategy strategy) {
    switch (strategy) {
      case OPTIMIZED_NFA:
      case OPTIMIZED_NFA_WITH_BACKREFS:
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
      case HYBRID_DFA_LOOKAHEAD:
      case SPECIALIZED_MULTIPLE_LOOKAHEADS:
      case SPECIALIZED_LITERAL_LOOKAHEADS:
      case LAZY_DFA:
      case BITSTATE_CAPTURE:
        return true;
      default:
        return false;
    }
  }

  /** Generate bytecode for the matcher class using the selected strategy. */
  private static byte[] generateBytecode(
      String pattern,
      PatternAnalyzer.MatchingStrategyResult result,
      NFA nfa,
      RegexNode ast,
      boolean caseInsensitive) {
    // Generate unique class name (UUID-based to avoid conflicts)
    String className = "ReggieMatcher$" + UUID.randomUUID().toString().replace("-", "");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    // Class declaration: public final class XXX extends ReggieMatcher [implements NfaStep]
    String[] ifaces =
        result.strategy == PatternAnalyzer.MatchingStrategy.LAZY_DFA
            ? new String[] {"com/datadoghq/reggie/runtime/NfaStep"}
            : null;
    cw.visit(
        V21,
        ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
        "com/datadoghq/reggie/runtime/" + className,
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        ifaces);

    // RECURSIVE_DESCENT strategy doesn't require additional instance fields
    // Fields are managed within the recursive parser methods

    // Generate constructor (with NFA state initialization for NFA-based strategies)
    boolean needsNFAState =
        result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA
            || result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
            || result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND
            || result.strategy == PatternAnalyzer.MatchingStrategy.HYBRID_DFA_LOOKAHEAD
            || result.strategy == PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTIPLE_LOOKAHEADS
            || result.strategy == PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS
            || result.strategy == PatternAnalyzer.MatchingStrategy.LAZY_DFA;
    boolean needsRecursiveDescent =
        result.strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT;
    generateConstructor(cw, pattern, className, needsNFAState, needsRecursiveDescent, nfa, ast);

    // Generate methods based on strategy (reuse existing generators)
    switch (result.strategy) {
      case STATELESS_LOOP:
        PatternAnalyzer.StatelessPatternInfo statelessInfo =
            (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;
        StatelessLoopBytecodeGenerator statelessGen =
            new StatelessLoopBytecodeGenerator(statelessInfo);
        statelessGen.generateHelperMethods(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        statelessGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_LITERAL_ALTERNATION:
        PatternAnalyzer.LiteralAlternationInfo literalAltInfo =
            (PatternAnalyzer.LiteralAlternationInfo) result.patternInfo;
        LiteralAlternationTrieGenerator literalAltGen =
            new LiteralAlternationTrieGenerator(literalAltInfo, nfa.getGroupCount());
        literalAltGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_GREEDY_CHARCLASS:
        PatternAnalyzer.GreedyCharClassInfo greedyInfo =
            (PatternAnalyzer.GreedyCharClassInfo) result.patternInfo;
        GreedyCharClassBytecodeGenerator greedyGen =
            new GreedyCharClassBytecodeGenerator(greedyInfo, nfa.getGroupCount());
        greedyGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        greedyGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case BITSTATE_BYTECODE:
        PatternAnalyzer.PrefixGuardedScanInfo prefixGuardedInfo =
            (PatternAnalyzer.PrefixGuardedScanInfo) result.patternInfo;
        BitStateBytecodeGenerator bitStateGen =
            new BitStateBytecodeGenerator(prefixGuardedInfo, nfa.getGroupCount());
        bitStateGen.generateAll(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_MULTI_GROUP_GREEDY:
        PatternAnalyzer.MultiGroupGreedyInfo multiGroupInfo =
            (PatternAnalyzer.MultiGroupGreedyInfo) result.patternInfo;
        MultiGroupGreedyBytecodeGenerator multiGroupGen =
            new MultiGroupGreedyBytecodeGenerator(multiGroupInfo, nfa.getGroupCount());
        multiGroupGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateMatchFromPositionMethod(
            cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        multiGroupGen.generateTryMatchBoundsFromPositionMethod(
            cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_CONCAT_GREEDY_GROUP:
        ConcatGreedyGroupInfo concatGreedyInfo = (ConcatGreedyGroupInfo) result.patternInfo;
        ConcatGreedyGroupBytecodeGenerator concatGreedyGen =
            new ConcatGreedyGroupBytecodeGenerator(
                concatGreedyInfo, "com.datadoghq.reggie.runtime." + className);
        concatGreedyGen.generate(cw);
        break;

      case SPECIALIZED_QUANTIFIED_GROUP:
        if (result.patternInfo instanceof QuantifiedGroupInfo) {
          QuantifiedGroupInfo quantifiedGroupInfo = (QuantifiedGroupInfo) result.patternInfo;
          QuantifiedGroupBytecodeGenerator quantifiedGroupGen =
              new QuantifiedGroupBytecodeGenerator(
                  quantifiedGroupInfo, "com.datadoghq.reggie.runtime." + className);
          quantifiedGroupGen.generate(cw);
        } else if (result.patternInfo instanceof ConcatQuantifiedGroupsInfo) {
          ConcatQuantifiedGroupsInfo concatGroupsInfo =
              (ConcatQuantifiedGroupsInfo) result.patternInfo;
          ConcatQuantifiedGroupsBytecodeGenerator concatGroupsGen =
              new ConcatQuantifiedGroupsBytecodeGenerator(
                  concatGroupsInfo, "com.datadoghq.reggie.runtime." + className);
          concatGroupsGen.generate(cw);
        }
        break;

      case SPECIALIZED_FIXED_SEQUENCE:
        PatternAnalyzer.FixedSequenceInfo fixedInfo =
            (PatternAnalyzer.FixedSequenceInfo) result.patternInfo;
        FixedSequenceBytecodeGenerator fixedGen =
            new FixedSequenceBytecodeGenerator(fixedInfo, nfa.getGroupCount());
        if (fixedGen.needsBoundaryHelpers()) {
          fixedGen.generateBoundaryHelperMethods(cw, "com/datadoghq/reggie/runtime/" + className);
        }
        fixedGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        fixedGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_BOUNDED_QUANTIFIERS:
        PatternAnalyzer.BoundedQuantifierInfo boundedInfo =
            (PatternAnalyzer.BoundedQuantifierInfo) result.patternInfo;
        BoundedQuantifierBytecodeGenerator boundedGen =
            new BoundedQuantifierBytecodeGenerator(boundedInfo, nfa.getGroupCount());
        boundedGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        boundedGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case ONEPASS_NFA:
        OnePassBytecodeGenerator onePass = new OnePassBytecodeGenerator(nfa);
        onePass.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateMatchInRangeMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case DFA_UNROLLED:
      case DFA_UNROLLED_WITH_ASSERTIONS:
      case DFA_UNROLLED_WITH_GROUPS:
        DFAUnrolledBytecodeGenerator unrolled =
            new DFAUnrolledBytecodeGenerator(
                result.dfa, nfa.getGroupCount(), result.useTaggedDFA, nfa);
        unrolled.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateMatchesAtStartMethod(cw); // Required by findFrom
        unrolled.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        // Private helper for match(): no priority-cut, used when scanPath would stop too early.
        if (result.useTaggedDFA && nfa.getGroupCount() > 0) {
          unrolled.generateFindMatchFromMethodTaggedNoCut(
              cw, "com/datadoghq/reggie/runtime/" + className);
        }
        unrolled.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateFindLongestMatchEndMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case DFA_SWITCH:
      case DFA_SWITCH_WITH_ASSERTIONS:
      case DFA_SWITCH_WITH_GROUPS:
        DFASwitchBytecodeGenerator switchGen =
            new DFASwitchBytecodeGenerator(result.dfa, nfa.getGroupCount(), nfa);
        switchGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateMatchesAtStartMethod(cw); // Required by findFrom
        switchGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        // matchBounded is inherited from the base default, which delegates to the (correct)
        // generated match(); the generated DFA-switch matchBounded returned null for whole-region
        // matches.
        switchGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case DFA_TABLE:
        DFATableBytecodeGenerator tableGen = new DFATableBytecodeGenerator(result.dfa);
        tableGen.generateStaticData(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        tableGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case BITPARALLEL_GLUSHKOV:
        BitParallelGlushkovBytecodeGenerator glushkovGen =
            new BitParallelGlushkovBytecodeGenerator(GlushkovAutomaton.from(nfa));
        glushkovGen.generateStaticData(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        glushkovGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case COUNTING_GLUSHKOV:
        PatternAnalyzer.CountingGlushkovInfo cgInfo =
            (PatternAnalyzer.CountingGlushkovInfo) result.patternInfo;
        CountingGlushkovBytecodeGenerator cgGen =
            new CountingGlushkovBytecodeGenerator(
                cgInfo.base, cgInfo.counterMin, cgInfo.counterMax);
        cgGen.generateStaticData(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        cgGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case FIXED_REPETITION_BACKREF:
        FixedRepetitionBackrefInfo fixedRepBackrefInfo =
            (FixedRepetitionBackrefInfo) result.patternInfo;
        FixedRepetitionBackrefBytecodeGenerator fixedRepGen =
            new FixedRepetitionBackrefBytecodeGenerator(
                fixedRepBackrefInfo, "com/datadoghq/reggie/runtime/" + className);
        fixedRepGen.generateMatchesMethod(cw);
        fixedRepGen.generateFindMethod(cw);
        fixedRepGen.generateFindFromMethod(cw);
        fixedRepGen.generateMatchMethod(cw);
        fixedRepGen.generateFindMatchMethod(cw);
        fixedRepGen.generateFindMatchFromMethod(cw);
        fixedRepGen.generateMatchesBoundedMethod(cw);
        fixedRepGen.generateMatchBoundedMethod(cw);
        break;

      case PINNED_BACKREFERENCE:
        PinnedBackreferenceInfo pinnedBackrefInfo = (PinnedBackreferenceInfo) result.patternInfo;
        PinnedBackreferenceBytecodeGenerator pinnedBackrefGen =
            new PinnedBackreferenceBytecodeGenerator(
                pinnedBackrefInfo, "com/datadoghq/reggie/runtime/" + className);
        pinnedBackrefGen.generateMatchesMethod(cw);
        pinnedBackrefGen.generateFindMethod(cw);
        pinnedBackrefGen.generateFindFromMethod(cw);
        pinnedBackrefGen.generateMatchMethod(cw);
        pinnedBackrefGen.generateFindMatchMethod(cw);
        pinnedBackrefGen.generateFindMatchFromMethod(cw);
        pinnedBackrefGen.generateMatchesBoundedMethod(cw);
        break;

      case SPECIALIZED_OPTIONAL_GROUP:
        SpecializedOptionalGroupInfo specOptGroupInfo =
            (SpecializedOptionalGroupInfo) result.patternInfo;
        SpecializedOptionalGroupBytecodeGenerator specOptGroupGen =
            new SpecializedOptionalGroupBytecodeGenerator(
                specOptGroupInfo, "com/datadoghq/reggie/runtime/" + className);
        specOptGroupGen.generateMatchesMethod(cw);
        specOptGroupGen.generateFindMethod(cw);
        specOptGroupGen.generateFindFromMethod(cw);
        specOptGroupGen.generateMatchMethod(cw);
        specOptGroupGen.generateFindMatchMethod(cw);
        specOptGroupGen.generateFindMatchFromMethod(cw);
        break;

      case VARIABLE_CAPTURE_BACKREF:
        VariableCaptureBackrefInfo varCaptureBackrefInfo =
            (VariableCaptureBackrefInfo) result.patternInfo;
        VariableCaptureBackrefBytecodeGenerator varCaptureGen =
            new VariableCaptureBackrefBytecodeGenerator(
                varCaptureBackrefInfo, "com/datadoghq/reggie/runtime/" + className);
        varCaptureGen.generate(cw);
        break;

      case OPTIONAL_GROUP_BACKREF:
        OptionalGroupBackrefInfo optGroupBackrefInfo =
            (OptionalGroupBackrefInfo) result.patternInfo;
        OptionalGroupBackrefBytecodeGenerator optGroupGen =
            new OptionalGroupBackrefBytecodeGenerator(
                optGroupBackrefInfo, "com/datadoghq/reggie/runtime/" + className);
        optGroupGen.generate(cw);
        break;

      case NESTED_QUANTIFIED_GROUPS:
        NestedQuantifiedGroupsInfo nestedGroupsInfo =
            (NestedQuantifiedGroupsInfo) result.patternInfo;
        NestedQuantifiedGroupsBytecodeGenerator nestedGen =
            new NestedQuantifiedGroupsBytecodeGenerator(
                nestedGroupsInfo, "com/datadoghq/reggie/runtime/" + className);
        nestedGen.generate(cw);
        break;

      case LINEAR_BACKREFERENCE:
        LinearPatternInfo linearInfo = (LinearPatternInfo) result.patternInfo;
        LinearPatternBytecodeGenerator linearGen =
            new LinearPatternBytecodeGenerator(linearInfo, caseInsensitive);
        linearGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        linearGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_BACKREFERENCE:
        BackreferencePatternInfo backrefInfo = (BackreferencePatternInfo) result.patternInfo;
        BackreferenceBytecodeGenerator backrefGen = new BackreferenceBytecodeGenerator(backrefInfo);
        backrefGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        backrefGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case SPECIALIZED_LITERAL_LOOKAHEADS: // Optimize lookaheads with extractable literals using
        // indexOf()
        com.datadoghq.reggie.codegen.analysis.LiteralLookaheadPatternInfo literalInfo =
            (com.datadoghq.reggie.codegen.analysis.LiteralLookaheadPatternInfo) result.patternInfo;
        NFABytecodeGenerator literalGen =
            new NFABytecodeGenerator(
                nfa,
                null,
                literalInfo,
                result.requiredLiterals,
                result.lookaheadGreedyInfo,
                result.usePosixLastMatch,
                caseInsensitive);
        literalGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateFindLongestMatchEndMethod(
            cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case HYBRID_DFA_LOOKAHEAD:
      case SPECIALIZED_MULTIPLE_LOOKAHEADS: // Tier 3: Same as HYBRID but with 2+ lookaheads
        // Hybrid strategy: use DFA for lookahead sub-patterns, NFA for main pattern
        PatternAnalyzer.HybridDFALookaheadInfo hybridInfo =
            (PatternAnalyzer.HybridDFALookaheadInfo) result.patternInfo;
        NFABytecodeGenerator hybridGen =
            new NFABytecodeGenerator(
                nfa,
                hybridInfo,
                null,
                result.requiredLiterals,
                result.lookaheadGreedyInfo,
                result.usePosixLastMatch,
                caseInsensitive);
        hybridGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindLongestMatchEndMethod(
            cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case OPTIMIZED_NFA_WITH_BACKREFS:
        {
          NFABytecodeGenerator nfaGen =
              new NFABytecodeGenerator(
                  nfa,
                  null,
                  null,
                  result.requiredLiterals,
                  result.lookaheadGreedyInfo,
                  result.usePosixLastMatch,
                  caseInsensitive,
                  true);
          nfaGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchBoundedMethod(
              cw, "com/datadoghq/reggie/runtime/" + className); // Phase 1.1 optimization
          nfaGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchBoundedCharSequenceMethod(
              cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          // Do not override findBoundsFrom: the base-class default delegates to findMatchFrom,
          // which preserves backref group semantics. findLongestMatchEnd runs without group
          // tracking and would skip backrefCheck states, producing incorrect bounds.
          break;
        }
      case LAZY_DFA:
        {
          LazyDFABytecodeGenerator lazyGen = new LazyDFABytecodeGenerator(nfa);
          lazyGen.generateStaticFields(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateNfaStepMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          // apply() satisfies NfaStep; matches/match/matchesBounded/matchBounded are compact.
          lazyGen.generateApplyMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          // find/findFrom use the DFA cache for O(n) scanning; match-span methods that need
          // the exact match end still delegate to the NFA for findLongestMatchEnd.
          NFABytecodeGenerator nfaDelegate =
              new NFABytecodeGenerator(
                  nfa,
                  null,
                  null,
                  result.requiredLiterals,
                  result.lookaheadGreedyInfo,
                  result.usePosixLastMatch,
                  caseInsensitive);
          // String overload of matchBounded called internally by findMatchFrom/findMatch.
          // Uses a compact stub (no NFA bytecode) to avoid the 64 KB method size limit.
          lazyGen.generateMatchBoundedStringMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          lazyGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaDelegate.generateFindLongestMatchEndMethod(
              cw, "com/datadoghq/reggie/runtime/" + className);
          nfaDelegate.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaDelegate.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaDelegate.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          break;
        }

      case OPTIMIZED_NFA:
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
        {
          NFABytecodeGenerator nfaGen =
              new NFABytecodeGenerator(
                  nfa,
                  null,
                  null,
                  result.requiredLiterals,
                  result.lookaheadGreedyInfo,
                  result.usePosixLastMatch,
                  caseInsensitive);
          nfaGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateMatchBoundedMethod(
              cw, "com/datadoghq/reggie/runtime/" + className); // Phase 1.1 optimization
          nfaGen.generateFindLongestMatchEndMethod(
              cw, "com/datadoghq/reggie/runtime/" + className); // Helper for greedy optimization
          nfaGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          break;
        }

      case RECURSIVE_DESCENT:
        // Context-free patterns: subroutines, conditionals, branch reset
        // Requires specialized recursive descent parser
        com.datadoghq.reggie.codegen.codegen.RecursiveDescentBytecodeGenerator recursiveGen =
            new com.datadoghq.reggie.codegen.codegen.RecursiveDescentBytecodeGenerator(ast, nfa);

        // IMPORTANT: Generate parser methods FIRST (parseRoot and AST parsers)
        // The public API methods depend on these parser methods being present
        // S: Backend choice - ASM (mandatory for Java 21)
        // S: Idempotence strategy - N/A (generates fresh class each time)
        // S: Stack annotations - Added throughout RecursiveDescentBytecodeGenerator
        recursiveGen.generateAllParserMethods(cw, "com/datadoghq/reggie/runtime/" + className);

        // Now generate public API methods (these call the parser methods)
        recursiveGen.generateMatchesMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateMatchIntoMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        recursiveGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case GREEDY_BACKTRACK:
        // Greedy patterns needing backtracking: (.*)bar, (.*)(\d+)
        GreedyBacktrackInfo greedyBacktrackInfo = (GreedyBacktrackInfo) result.patternInfo;
        GreedyBacktrackBytecodeGenerator greedyBacktrackGen =
            new GreedyBacktrackBytecodeGenerator(
                greedyBacktrackInfo, "com.datadoghq.reggie.runtime." + className);
        greedyBacktrackGen.generate(cw);
        break;

      default:
        throw new IllegalStateException("Unknown strategy: " + result.strategy);
    }

    cw.visitEnd();
    byte[] bytecode = cw.toByteArray();

    // Debug: Trace bytecode if system property is set
    String tracePattern = System.getProperty("reggie.debug.trace");
    if (tracePattern != null && pattern.equals(tracePattern)) {
      System.out.println("=== BYTECODE TRACE FOR PATTERN: " + pattern + " ===");
      System.out.println("Strategy: " + result.strategy);
      ClassReader cr = new ClassReader(bytecode);
      TraceClassVisitor tcv = new TraceClassVisitor(new PrintWriter(System.out, true));
      cr.accept(tcv, 0);
      System.out.println("=== END BYTECODE TRACE ===");
    }

    // Debug: Write bytecode to file if system property is set
    String debugDir = System.getProperty("reggie.debug.bytecode");
    if (debugDir != null) {
      try {
        java.nio.file.Path dir = java.nio.file.Paths.get(debugDir);
        java.nio.file.Files.createDirectories(dir);
        String debugFileName =
            "Matcher_" + pattern.replaceAll("[^a-zA-Z0-9]", "_") + "_" + result.strategy + ".class";
        java.nio.file.Path classFile = dir.resolve(debugFileName);
        java.nio.file.Files.write(classFile, bytecode);
        System.out.println("DEBUG: Wrote bytecode to " + classFile);
      } catch (Exception ex) {
        System.err.println("DEBUG: Failed to write bytecode: " + ex.getMessage());
      }
    }

    return bytecode;
  }

  /**
   * Generate constructor that accepts pattern string and calls super(pattern). This allows the same
   * class to be reused with different patterns (structural caching).
   */
  private static void generateConstructor(
      ClassWriter cw,
      String pattern,
      String className,
      boolean needsNFAState,
      boolean needsRecursiveDescent,
      NFA nfa,
      RegexNode ast) {
    // Generate constructor with String parameter: (Ljava/lang/String;)V
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
    mv.visitCode();

    // Load 'this'
    mv.visitVarInsn(ALOAD, 0);

    // Load pattern string parameter (from constructor argument)
    mv.visitVarInsn(ALOAD, 1);

    // Call super(pattern)
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        "<init>",
        "(Ljava/lang/String;)V",
        false);

    // Initialize NFA state for NFA-based strategies
    if (needsNFAState) {
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitLdcInsn(nfa.getStates().size()); // stateCount
      mv.visitLdcInsn(nfa.getGroupCount()); // groupCount
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "initNFAState",
          "(II)V",
          false);
    }

    if (needsRecursiveDescent) {
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitLdcInsn(nfa != null ? nfa.getGroupCount() : countGroups(pattern)); // groupCount
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "initRecursiveState",
          "(I)V",
          false);
    }

    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Count capturing groups in pattern (simple heuristic). This is a simplified implementation -
   * proper counting happens in parser.
   */
  /**
   * Check if pattern has global case-insensitive mode (?i). Returns true if pattern starts with
   * (?i) or has (?i) at the beginning.
   */
  private static boolean isCaseInsensitive(String pattern) {
    if (pattern == null || pattern.length() < 4) {
      return false;
    }
    // Check for (?i) at start or after initial anchors
    int startIdx = 0;
    if (pattern.charAt(0) == '^') {
      startIdx = 1;
    }
    if (startIdx + 3 < pattern.length()
        && pattern.charAt(startIdx) == '('
        && pattern.charAt(startIdx + 1) == '?'
        && pattern.charAt(startIdx + 2) == 'i'
        && pattern.charAt(startIdx + 3) == ')') {
      return true;
    }
    return false;
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\' && i + 1 < pattern.length() && pattern.charAt(i + 1) == 'Q') {
        i += 2;
        while (i + 1 < pattern.length()
            && !(pattern.charAt(i) == '\\' && pattern.charAt(i + 1) == 'E')) {
          i++;
        }
        if (i + 1 < pattern.length()) {
          i++;
        }
        continue;
      }
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length()) {
        // Check if it's a capturing group
        // Named groups like (?<name>...) and (?'name'...) ARE capturing groups
        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
          // Check what follows the '?'
          if (i + 2 < pattern.length()) {
            char next = pattern.charAt(i + 2);
            if (next == ':') {
              continue; // Non-capturing (?:...)
            }
            if (next == '=' || next == '!') {
              continue; // Lookahead (?=...) or (?!...)
            }
            if (next == '<' && i + 3 < pattern.length()) {
              char afterLt = pattern.charAt(i + 3);
              if (afterLt == '=' || afterLt == '!') {
                continue; // Lookbehind (?<=...) or (?<!...)
              }
              // (?<name>...) is a named capturing group, count it
            }
            if (next == '>') {
              continue; // Atomic group (?>...)
            }
            if (next == '#') {
              continue; // Comment (?#...)
            }
            if (next == '|') {
              continue; // Branch reset (?|...)
            }
            if (next == '(') {
              continue; // Conditional (?(...)...)
            }
            // Check for inline modifiers like (?i), (?m), (?s), (?x), (?-i), etc.
            if (next == '-'
                || next == 'i'
                || next == 'm'
                || next == 's'
                || next == 'x'
                || next == 'u'
                || next == 'U'
                || next == 'd') {
              continue; // Inline modifier (?i...) etc.
            }
          }
        }
        count++;
      }
    }
    return count;
  }
}
