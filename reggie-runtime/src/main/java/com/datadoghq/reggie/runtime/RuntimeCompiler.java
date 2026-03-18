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

import com.datadoghq.reggie.codegen.analysis.BackreferencePatternInfo;
import com.datadoghq.reggie.codegen.analysis.ConcatGreedyGroupInfo;
import com.datadoghq.reggie.codegen.analysis.ConcatQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.FixedRepetitionBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.GreedyBacktrackInfo;
import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo;
import com.datadoghq.reggie.codegen.analysis.NestedQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.QuantifiedGroupInfo;
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
import com.datadoghq.reggie.codegen.codegen.DFAUnrolledBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedRepetitionBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedSequenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedyBacktrackBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedyCharClassBytecodeGenerator;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

  // Level 1: Pattern string → matcher instance (fast path for exact matches)
  private static final ConcurrentHashMap<String, ReggieMatcher> PATTERN_CACHE =
      new ConcurrentHashMap<>();

  // Level 2: Structural hash → generated class (deduplication for similar patterns)
  private static final ConcurrentHashMap<Integer, Class<? extends ReggieMatcher>> STRUCTURE_CACHE =
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
    return PATTERN_CACHE.computeIfAbsent(pattern, RuntimeCompiler::compileInternal);
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
    return PATTERN_CACHE.computeIfAbsent(key, k -> compileInternal(pattern));
  }

  /** Clear both pattern and structure caches. */
  public static void clearCache() {
    PATTERN_CACHE.clear();
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
    try {
      // 1. Parse pattern to AST
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);

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

      // 4. Check if we should use hybrid mode (DFA + NFA for groups)
      if (groupCount > 0 && shouldUseHybrid(result)) {
        return compileHybrid(pattern, ast, nfa, analyzer, result, caseInsensitive);
      }

      // 5. Compute structural hash for level 2 cache lookup
      int structHash =
          (nfa != null)
              ? StructuralHash.compute(result, nfa, caseInsensitive)
              : StructuralHash.computeWithoutGroupCount(result);

      // 6. Check structural cache (level 2)
      Class<? extends ReggieMatcher> matcherClass = STRUCTURE_CACHE.get(structHash);

      if (matcherClass != null) {
        // Cache hit: Reuse existing class, instantiate with current pattern
        return instantiateFromClass(matcherClass, pattern);
      }

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

      // 9. Instantiate and return matcher
      return instantiateFromClass(matcherClass, pattern);

    } catch (PatternSyntaxException e) {
      // Re-throw PatternSyntaxException as-is
      throw e;
    } catch (Exception e) {
      // Wrap other exceptions
      throw new RuntimeException("Failed to compile pattern: " + pattern, e);
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

    // Class declaration: public final class XXX extends ReggieMatcher
    cw.visit(
        V21,
        ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
        "com/datadoghq/reggie/runtime/" + className,
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        null);

    // RECURSIVE_DESCENT strategy doesn't require additional instance fields
    // Fields are managed within the recursive parser methods

    // Generate constructor (with NFA state initialization for NFA-based strategies)
    boolean needsNFAState =
        result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA
            || result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
            || result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND
            || result.strategy == PatternAnalyzer.MatchingStrategy.HYBRID_DFA_LOOKAHEAD;
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
        literalAltGen.generateMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        literalAltGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
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
        unrolled.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        unrolled.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
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
        switchGen.generateMatchesBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        switchGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
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
        // Generate match() with full group capture support
        fixedRepGen.generateMatchMethod(cw);
        // Generate stub methods for remaining MatchResult-returning methods
        fixedRepGen.generateMatchesBoundedStubMethod(cw);
        fixedRepGen.generateMatchBoundedStubMethod(cw);
        fixedRepGen.generateFindMatchStubMethod(cw);
        fixedRepGen.generateFindMatchFromStubMethod(cw);
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
        hybridGen.generateMatchBoundedMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindLongestMatchEndMethod(
            cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        hybridGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

      case OPTIMIZED_NFA:
      case OPTIMIZED_NFA_WITH_BACKREFS:
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
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
        nfaGen.generateMatchBoundedMethod(
            cw, "com/datadoghq/reggie/runtime/" + className); // Phase 1.1 optimization
        nfaGen.generateFindLongestMatchEndMethod(
            cw, "com/datadoghq/reggie/runtime/" + className); // Helper for greedy optimization
        nfaGen.generateFindMatchMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        nfaGen.generateFindMatchFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        nfaGen.generateFindBoundsFromMethod(cw, "com/datadoghq/reggie/runtime/" + className);
        break;

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

    // Recursive descent doesn't need special constructor initialization
    // Parser state is managed in the parser methods themselves

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
