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

import com.datadoghq.reggie.CapturePolicy;
import com.datadoghq.reggie.ReggieOptions;
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
import com.datadoghq.reggie.codegen.analysis.QuantifiedGroupInfo;
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier;
import com.datadoghq.reggie.codegen.analysis.StructuralHash;
import com.datadoghq.reggie.codegen.analysis.VariableCaptureBackrefInfo;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.codegen.BackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.BoundedQuantifierBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.ConcatGreedyGroupBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.ConcatQuantifiedGroupsBytecodeGenerator;
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
import com.datadoghq.reggie.codegen.codegen.QuantifiedGroupBytecodeGenerator;
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

  // Patterns for which a RICH_API_HYBRID warning has already been logged, so the loud
  // "delegates to java.util.regex" warning fires at most once per pattern even though
  // compileInternal may run more than once for the same pattern under cache races.
  private static final Set<String> HYBRID_WARNED = ConcurrentHashMap.newKeySet();

  // Level 1: Pattern string → matcher instance (fast path for exact matches).
  // NFA-backed patterns are NOT stored here; they use NFA_CLASS_CACHE instead.
  private static final ConcurrentHashMap<String, ReggieMatcher> PATTERN_CACHE =
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
        m = new NameEnrichingMatcher(m);
      }
      return m;
    }
  }

  // Level 1b: Pattern string → NFA matcher factory for NFA-backed strategies.
  // NFA matchers mutate shared instance fields (currentStates, nextStates, epsilonProcessed,
  // configGroupStarts) during matching, so a single shared instance cannot be used concurrently.
  // We cache a factory here that produces a fresh enriched instance on every compile() call.
  private static final ConcurrentHashMap<String, NfaMatcherFactory> NFA_CLASS_CACHE =
      new ConcurrentHashMap<>();

  // Level 1c: Pattern string → NFA for PIKEVM_CAPTURE patterns.
  // PikeVMMatcher carries mutable per-call buffers and must be freshly instantiated on every
  // compile() call; only the NFA (immutable after construction) is cached.
  private static final ConcurrentHashMap<String, NFA> PIKEVM_NFA_CACHE = new ConcurrentHashMap<>();

  // Level 2: Structural hash → generated class (deduplication for similar patterns).
  // Key is Long (64-bit) to make birthday collisions essentially impossible across large pattern
  // sets; an int key was observed to cause structural-cache false-hits with wrong match semantics.
  private static final ConcurrentHashMap<Long, Class<? extends ReggieMatcher>> STRUCTURE_CACHE =
      new ConcurrentHashMap<>();

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

  /** Compile pattern with runtime compilation options. */
  public static ReggieMatcher compile(String pattern, ReggieOptions options) {
    String cacheKey =
        options.capturePolicy() == CapturePolicy.ALL
            ? pattern
            : pattern + "\u0000capturePolicy=" + options.capturePolicy();

    // Fast path: PIKEVM_CAPTURE patterns are in PIKEVM_NFA_CACHE — return a fresh PikeVMMatcher.
    // PikeVMMatcher carries mutable per-call buffers and must not be shared across calls.
    NFA pikevmNfa = PIKEVM_NFA_CACHE.get(cacheKey);
    if (pikevmNfa != null) {
      return new PikeVMMatcher(pikevmNfa, pattern);
    }

    // Fast path: NFA-backed patterns are in NFA_CLASS_CACHE — return a fresh instance.
    // NFA matchers mutate shared instance fields during matching and cannot be shared across
    // threads or calls; we cache a factory and instantiate per-call instead.
    NfaMatcherFactory factory = NFA_CLASS_CACHE.get(cacheKey);
    if (factory != null) {
      return factory.newInstance(pattern);
    }

    // Slow path: compile and cache the result.
    // compileInternal will populate NFA_CLASS_CACHE if the strategy is NFA-backed, in which case
    // the L1 entry is immediately removed so that subsequent calls hit the fast path above.
    ReggieMatcher compiled =
        PATTERN_CACHE.computeIfAbsent(
            cacheKey,
            k ->
                options.capturePolicy() == CapturePolicy.ALL
                    ? compileInternal(pattern, ReggieOptions.DEFAULT, k)
                    : compileInternal(pattern, options, k));

    // Post-compilation fixup: if compileInternal registered this pattern as PIKEVM_CAPTURE,
    // remove it from L1 and return a fresh PikeVMMatcher so callers never share mutable state.
    pikevmNfa = PIKEVM_NFA_CACHE.get(cacheKey);
    if (pikevmNfa != null) {
      PATTERN_CACHE.remove(cacheKey, compiled);
      return new PikeVMMatcher(pikevmNfa, pattern);
    }

    // Post-compilation fixup: if compileInternal registered this pattern as NFA-backed,
    // remove it from L1 and return a fresh instance so callers never share NFA state.
    factory = NFA_CLASS_CACHE.get(cacheKey);
    if (factory != null) {
      PATTERN_CACHE.remove(cacheKey, compiled);
      return factory.newInstance(pattern);
    }
    return compiled;
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
    STRUCTURE_CACHE.clear();
    HYBRID_WARNED.clear();
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

  /** Get all cached pattern keys. */
  public static Set<String> cachedPatterns() {
    return PATTERN_CACHE.keySet();
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

  /**
   * Compile a pattern with an explicit L1 cache key. When the strategy is NFA-backed, the compiled
   * class is stored in NFA_CLASS_CACHE under {@code cacheKey} so that compile() can skip L1 and
   * return a fresh instance on every subsequent call.
   */
  private static ReggieMatcher compileInternal(
      String pattern, ReggieOptions options, String cacheKey) {
    try {
      // 1. Parse pattern to AST
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);
      Map<String, Integer> nameMap = parser.getGroupNameMap();
      if (options.capturePolicy() == CapturePolicy.NAMED_ONLY) {
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

      // 3.5. Fall back to java.util.regex for patterns with known engine bugs
      if (result.anchorConditionDiluted) {
        ReggieMatcher fallback =
            new JavaRegexFallbackMatcher(pattern, "anchor condition diluted in DFA construction");
        if (!nameMap.isEmpty()) {
          fallback.setNameToIndex(nameMap);
        }
        return fallback;
      }
      if (result.alternationPriorityConflict) {
        ReggieMatcher fallback =
            new JavaRegexFallbackMatcher(
                pattern,
                "alternation priority conflict: DFA longest-match vs NFA first-alternative");
        if (!nameMap.isEmpty()) {
          fallback.setNameToIndex(nameMap);
        }
        return fallback;
      }
      if (result.captureAmbiguous) {
        ReggieMatcher fallback =
            new JavaRegexFallbackMatcher(
                pattern,
                "capture-ambiguous group bindings: group spans require java.util.regex semantics");
        if (!nameMap.isEmpty()) {
          fallback.setNameToIndex(nameMap);
        }
        return fallback;
      }

      // 3.6. PIKEVM_CAPTURE: return a fresh PikeVMMatcher backed by the already-built NFA.
      // PikeVMMatcher has mutable per-call buffers; cache only the NFA so each compile() call
      // produces a fresh instance without rebuilding the NFA.
      if (result.strategy == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE) {
        PIKEVM_NFA_CACHE.putIfAbsent(cacheKey, nfa);
        ReggieMatcher m = new PikeVMMatcher(PIKEVM_NFA_CACHE.get(cacheKey), pattern);
        if (!nameMap.isEmpty()) {
          m.setNameToIndex(nameMap);
          m = new NameEnrichingMatcher(m);
        }
        return m;
      }

      String fallbackReason = FallbackPatternDetector.needsFallback(ast, result.strategy);
      if (fallbackReason != null) {
        ReggieMatcher fallback = new JavaRegexFallbackMatcher(pattern, fallbackReason);
        if (!nameMap.isEmpty()) {
          fallback.setNameToIndex(nameMap);
        }
        return fallback;
      }
      // Fall back for lookahead strategies whose generated boolean engine is itself incorrect
      // (find()/findFrom() never report a match, and matches() accepts embedded matches). These
      // cannot keep a "fast boolean path" because that path is wrong; delegate the whole matcher to
      // java.util.regex so booleans and group spans are both correct.
      String lookaheadDefect = lookaheadBooleanEngineDefectReason(result.strategy);
      if (lookaheadDefect != null) {
        ReggieMatcher fallback = new JavaRegexFallbackMatcher(pattern, lookaheadDefect);
        if (!nameMap.isEmpty()) {
          fallback.setNameToIndex(nameMap);
        }
        return fallback;
      }
      // Fall back for strategies whose MatchResult API (match, findMatch, bounded methods) is not
      // yet fully implemented. Callers get correct behavior via JDK until implementation is
      // complete.
      String incompleteApiReason = incompleteMatchResultApiReason(result.strategy);
      if (incompleteApiReason != null) {
        ReggieMatcher fallback = new JavaRegexFallbackMatcher(pattern, incompleteApiReason);
        if (!nameMap.isEmpty()) {
          fallback.setNameToIndex(nameMap);
        }
        return fallback;
      }

      // 3.6. Loud warning for RICH_API_HYBRID strategies: native boolean matching but the rich
      // MatchResult API (match/findMatch/findMatchFrom/matchBounded) delegates to java.util.regex
      // via the base-class defaults. FULL_FALLBACK already warned above (and returned); this never
      // double-warns. Logged once per pattern.
      String hybridReason = StrategyJdkClassifier.richApiHybridReason(result.strategy);
      if (hybridReason != null && HYBRID_WARNED.add(pattern)) {
        LOG.warning(
            "Reggie compiled '"
                + pattern
                + "' to a HYBRID matcher: native boolean matching but group extraction"
                + " (match/findMatch) delegates to java.util.regex (strategy "
                + result.strategy
                + ").");
      }

      // 4. Check if we should use hybrid mode (DFA + NFA for groups)
      if (groupCount > 0 && shouldUseHybrid(result)) {
        ReggieMatcher hybrid = compileHybrid(pattern, ast, nfa, analyzer, result, caseInsensitive);
        hybrid.setNameToIndex(nameMap);
        return hybrid;
      }

      // 5. Compute structural hash for level 2 cache lookup (64-bit key)
      long structHash =
          (nfa != null)
              ? StructuralHash.compute(result, nfa, caseInsensitive)
              : StructuralHash.computeWithoutGroupCount(result);

      // 6. Check structural cache (level 2)
      Class<? extends ReggieMatcher> matcherClass = STRUCTURE_CACHE.get(structHash);

      if (matcherClass == null) {
        // 7. Cache miss: Generate bytecode and define hidden class
        byte[] bytecode = generateBytecode(pattern, result, nfa, ast, caseInsensitive);

        MethodHandles.Lookup hiddenLookup =
            LOOKUP.defineHiddenClass(
                bytecode,
                true, // initialize immediately
                MethodHandles.Lookup.ClassOption.NESTMATE);

        matcherClass = hiddenLookup.lookupClass().asSubclass(ReggieMatcher.class);

        // 8. Cache the generated class for future patterns with same structure
        STRUCTURE_CACHE.put(structHash, matcherClass);
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
        matcher = new NameEnrichingMatcher(matcher);
      }

      return matcher;

    } catch (org.objectweb.asm.MethodTooLargeException e) {
      // Very large grok-style alternations can exceed JVM method-size limits. Preserve drop-in
      // behavior by falling back to java.util.regex instead of failing compilation, but include the
      // generated method and bytecode size in the warning so routing/generator fixes can be guided
      // by real-world patterns.
      ReggieMatcher fallback =
          new JavaRegexFallbackMatcher(
              pattern,
              "generated method too large: "
                  + e.getClassName()
                  + "."
                  + e.getMethodName()
                  + e.getDescriptor()
                  + " codeSize="
                  + e.getCodeSize());
      return fallback;
    } catch (RegexParser.UnsupportedPatternException | UnsupportedOperationException e) {
      throw new com.datadoghq.reggie.UnsupportedPatternException(
          "Unsupported regex pattern: " + pattern + ": " + e.getMessage(), e);
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
        .map(NameEnrichingMatcher::new)
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
   * /** Returns a non-null reason string when the selected strategy has an incomplete MatchResult
   * API (match, findMatch, matchBounded, etc. not yet implemented). The caller falls back to JDK.
   */
  private static String incompleteMatchResultApiReason(PatternAnalyzer.MatchingStrategy strategy) {
    switch (strategy) {
      case VARIABLE_CAPTURE_BACKREF:
        return "MatchResult API not yet implemented for VARIABLE_CAPTURE_BACKREF strategy";
      case NESTED_QUANTIFIED_GROUPS:
        return "MatchResult API not yet implemented for NESTED_QUANTIFIED_GROUPS strategy";
      default:
        return null;
    }
  }

  /**
   * Strategies whose generated boolean engine is itself incorrect (not merely the rich MatchResult
   * API): {@code find()}/{@code findFrom()} never report a match and {@code matches()} accepts
   * embedded matches. The whole matcher must delegate to {@code java.util.regex} until the
   * generated lookahead engine is fixed; a "fast boolean path" cannot be kept because that path is
   * wrong.
   */
  private static String lookaheadBooleanEngineDefectReason(
      PatternAnalyzer.MatchingStrategy strategy) {
    switch (strategy) {
      case SPECIALIZED_MULTIPLE_LOOKAHEADS:
        return "boolean engine incorrect for SPECIALIZED_MULTIPLE_LOOKAHEADS strategy"
            + " (find/findFrom never match, matches accepts embedded matches)";
      case SPECIALIZED_LITERAL_LOOKAHEADS:
        return "boolean engine incorrect for SPECIALIZED_LITERAL_LOOKAHEADS strategy"
            + " (find/findFrom never match, matches accepts embedded matches)";
      case HYBRID_DFA_LOOKAHEAD:
        return "boolean engine incorrect for HYBRID_DFA_LOOKAHEAD strategy"
            + " (find/findFrom never match)";
      default:
        return null;
    }
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
      PatternAnalyzer analyzer,
      PatternAnalyzer.MatchingStrategyResult originalResult,
      boolean caseInsensitive)
      throws Exception {
    // 1. Get DFA strategy (ignore group count)
    PatternAnalyzer.MatchingStrategyResult dfaResult = analyzer.analyzeAndRecommend(true);

    // If DFA construction failed or pattern needs NFA anyway, fall back to pure NFA
    if (dfaResult.dfa == null) {
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
        // Rich MatchResult methods (match/findMatch/findMatchFrom and bounded variants) are
        // inherited from the JDK-backed defaults in ReggieMatcher; the generated trie versions
        // returned null spans. Boolean fast path (matches/find/findFrom) stays generated.
        literalAltGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
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
        onePass.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        onePass.generateMatchesInRangeMethod(cw, "com/datadoghq/reggie/runtime/" + className);
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

      case FIXED_REPETITION_BACKREF:
        FixedRepetitionBackrefInfo fixedRepBackrefInfo =
            (FixedRepetitionBackrefInfo) result.patternInfo;
        FixedRepetitionBackrefBytecodeGenerator fixedRepGen =
            new FixedRepetitionBackrefBytecodeGenerator(
                fixedRepBackrefInfo, "com/datadoghq/reggie/runtime/" + className);
        fixedRepGen.generateMatchesMethod(cw);
        fixedRepGen.generateFindMethod(cw);
        fixedRepGen.generateFindFromMethod(cw);
        // Rich MatchResult methods (match/findMatch/findMatchFrom and bounded variants) are
        // inherited from the JDK-backed defaults in ReggieMatcher. The generated match() reported
        // a whole-input match even when the input had unmatched trailing characters, and the other
        // rich methods were UnsupportedOperationException stubs. Boolean fast path stays generated.
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
          // find* methods use the standard NFA implementation (no capturing groups involved).
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
          nfaDelegate.generateFindMethod(cw, "com/datadoghq/reggie/runtime/" + className);
          nfaDelegate.generateFindFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
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
