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
package com.datadoghq.reggie.processor;

import static org.objectweb.asm.Opcodes.*;

import com.datadoghq.reggie.codegen.analysis.BackreferencePatternInfo;
import com.datadoghq.reggie.codegen.analysis.ConcatGreedyGroupInfo;
import com.datadoghq.reggie.codegen.analysis.ConcatQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.FallbackPatternDetector;
import com.datadoghq.reggie.codegen.analysis.FixedRepetitionBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.GreedySuffixInfo;
import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo;
import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.PinnedBackreferenceInfo;
import com.datadoghq.reggie.codegen.analysis.QuantifiedGroupInfo;
import com.datadoghq.reggie.codegen.analysis.RecursiveDescentChoiceAnalysis;
import com.datadoghq.reggie.codegen.analysis.SpecializedOptionalGroupInfo;
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier;
import com.datadoghq.reggie.codegen.analysis.VariableCaptureBackrefInfo;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.BacktrackSupport;
import com.datadoghq.reggie.codegen.automaton.DFA;
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
import com.datadoghq.reggie.codegen.codegen.DeterministicChainBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedRepetitionBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedSequenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedyCharClassBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedySuffixBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LazyDFABytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LinearPatternBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LiteralAlternationTrieGenerator;
import com.datadoghq.reggie.codegen.codegen.MultiGroupGreedyBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.NFABytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.OnePassBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.OptionalGroupBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.PinnedBackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.QuantifiedGroupBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.RecursiveDescentBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.SpecializedOptionalGroupBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.StatelessLoopBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.SuffixSequenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.VariableCaptureBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.parsing.GroupCounter;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.objectweb.asm.*;

/**
 * Generates bytecode for ReggieMatcher subclasses using ASM. Uses DFA/NFA pipeline: Pattern → AST →
 * NFA → Strategy → Bytecode.
 */
public class ReggieMatcherBytecodeGenerator {

  private final String className;
  private final String pattern;

  // The strategy selected for this pattern, recorded by generate() so callers (the annotation
  // processor) can classify the JDK dependency and emit the compile-time hybrid warning.
  private PatternAnalyzer.MatchingStrategy resolvedStrategy;

  public ReggieMatcherBytecodeGenerator(String packageName, String className, String pattern) {
    this.className = packageName.replace('.', '/') + "/" + className;
    this.pattern = pattern;
  }

  /**
   * Returns the {@link PatternAnalyzer.MatchingStrategy} selected during the most recent {@link
   * #generate()} or {@link #resolveRealization} call, or {@code null} if neither has completed yet.
   */
  public PatternAnalyzer.MatchingStrategy resolvedStrategy() {
    return resolvedStrategy;
  }

  /** How a {@code @RegexPattern} method should be realized. */
  public enum Realization {
    NATIVE,
    /**
     * Two generated classes, a captureless DFA half and an NFA half; see {@link #generateHybrid}.
     */
    HYBRID,
    DELEGATE_PIKEVM,
    /** Runs on the runtime backtracker; see {@code BacktrackSupport#routesToBacktracker}. */
    DELEGATE_BACKTRACK,
    DELEGATE_FALLBACK
  }

  /** Class-name suffix of the hybrid DFA half (appended to the matcher class name). */
  public static final String DFA_HALF_SUFFIX = "$Dfa";

  /** Class-name suffix of the hybrid NFA half (appended to the matcher class name). */
  public static final String NFA_HALF_SUFFIX = "$Nfa";

  /**
   * Bytecode of the two {@link Realization#HYBRID} classes. {@code lazyFind} is the {@code
   * HybridMatcher} flag: the DFA half was built with leftmost-first pruning.
   */
  public record HybridClasses(byte[] dfaHalf, byte[] nfaHalf, boolean lazyFind) {}

  /** The DFA half chosen for a hybrid: its analysis result and the NFA it was built from. */
  private record HybridPlan(
      PatternAnalyzer.MatchingStrategyResult dfaHalf, NFA dfaHalfNfa, boolean lazyFind) {}

  /**
   * Resolves how to realize this pattern. Throws {@link UnsupportedOperationException} when the
   * pattern requires JDK fallback and {@code allowJdkFallback} is false (build error). Populates
   * {@link #resolvedStrategy()} as a side effect.
   */
  public Realization resolveRealization(boolean allowJdkFallback) throws Exception {
    return resolveRealization(allowJdkFallback, true);
  }

  /**
   * As {@link #resolveRealization(boolean)}; with {@code oneWayRoute} false the one-way
   * recursive-descent route is skipped; RegexPatternProcessor uses it when that class exceeds the
   * JVM method size limit. A pattern java.util.regex cannot parse is never realized as {@link
   * Realization#DELEGATE_FALLBACK}.
   */
  public Realization resolveRealization(boolean allowJdkFallback, boolean oneWayRoute)
      throws Exception {
    Realization realization = resolve(allowJdkFallback, oneWayRoute);
    if (realization == Realization.DELEGATE_FALLBACK && !jdkParses(pattern)) {
      throw new UnsupportedOperationException(noJdkFallback(pattern));
    }
    return realization;
  }

  /** Error message for a pattern that needs java.util.regex, which cannot parse it. */
  static String noJdkFallback(String pattern) {
    return "Pattern '"
        + pattern
        + "' requires java.util.regex fallback, but java.util.regex cannot parse it.";
  }

  /** Whether java.util.regex parses {@code pattern}. */
  static boolean jdkParses(String pattern) {
    try {
      Pattern.compile(pattern);
      return true;
    } catch (PatternSyntaxException e) {
      return false;
    }
  }

  private Realization resolve(boolean allowJdkFallback, boolean oneWayRoute) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    int groupCount = GroupCounter.count(pattern);
    // As in RuntimeCompiler: the analysis of a context-free pattern (subroutine, conditional,
    // branch reset) needs no NFA.
    boolean recursiveDescent = PatternAnalyzer.requiresRecursiveDescent(ast);
    NFA nfa = recursiveDescent ? null : new ThompsonBuilder().build(ast, groupCount);
    if (nfa != null && nfa.hasCountedLoops()) {
      // The counted-loop lowering runs in this build too, but only the runtime pipeline
      // executes the markers (BackrefBacktrackMatcher). No compile-time generator interprets
      // them — the markers would read as dead states and reject valid matches.
      if (allowJdkFallback) {
        return Realization.DELEGATE_FALLBACK;
      }
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' uses a bounded quantifier over the unrolling budget; the counted-loop engine is"
              + " runtime-only. Use Reggie.compile() instead.");
    }
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
    this.resolvedStrategy = result.strategy;

    // BITSTATE_CAPTURE folds into the same branch as PIKEVM_CAPTURE: v1 intentionally does not add
    // a BitState-backed codegen path for the annotation-processor (compile-time) pipeline, so a
    // pattern that PatternAnalyzer#routeBitState substituted PIKEVM_CAPTURE→BITSTATE_CAPTURE for
    // still runs on PikeVM at runtime via compilePikeVm — only the same pattern compiled
    // dynamically via RuntimeCompiler.compile() gets the faster BitState engine. This is a known,
    // intentional v1 asymmetry (doc/2026-07-03-bitstate-capture-engine-design.md).
    if (result.strategy == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE
        || result.strategy == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE) {
      // Rebuild NFA with lazy-aware epsilon ordering for PikeVM correctness with lazy quantifiers.
      nfa = new ThompsonBuilder(true).build(ast, groupCount);
      String pikeVmFallbackReason =
          FallbackPatternDetector.needsFallback(
              ast, PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
      if (pikeVmFallbackReason != null) {
        if (allowJdkFallback) {
          return Realization.DELEGATE_FALLBACK;
        }
        throw new UnsupportedOperationException(
            "Pattern '"
                + pattern
                + "' requires java.util.regex fallback (strategy "
                + result.strategy
                + "): "
                + pikeVmFallbackReason
                + ". Add options = ReggieOption.ALLOW_JDK_FALLBACK to @RegexPattern to permit a"
                + " delegating stub, or use Reggie.compile() at runtime.");
      }
      return Realization.DELEGATE_PIKEVM;
    }
    // Mirrors RuntimeCompiler's one-way check before step 4: the recursive-descent generator
    // runs a pattern with no choice point it would have to retry.
    if (oneWayRoute && rdOneWay(ast, result)) {
      return Realization.NATIVE;
    }
    // Mirrors RuntimeCompiler step 4.5: patterns whose generated engine diverges from
    // java.util.regex run on the backtracker when it supports them. The DFA-level refusals
    // (anchor dilution, alternation-priority conflict, capture ambiguity) precede that step there
    // and stay with needsJdk here.
    boolean backtrackerRuns = false;
    String backtrackRefusal = "the backtracking engine does not route strategy " + result.strategy;
    if (!result.anchorConditionDiluted
        && !result.alternationPriorityConflict
        && !result.captureAmbiguous
        && (nfa != null || recursiveDescent)
        && BacktrackSupport.routesToBacktracker(result.strategy, nfa)) {
      try {
        String unsupported =
            BacktrackSupport.unsupportedFeature(
                new ThompsonBuilder(true, true).build(ast, groupCount));
        backtrackerRuns = unsupported == null;
        backtrackRefusal = "the backtracking engine does not execute " + unsupported;
      } catch (UnsupportedOperationException e) {
        // The state cap, or a call to a missing group: the backtracker cannot run it.
        backtrackRefusal = "the backtracking engine cannot build it: " + e.getMessage();
      }
    }
    if (backtrackerRuns) {
      String backtrackFallbackReason =
          FallbackPatternDetector.needsFallback(ast, result.strategy, true);
      if (backtrackFallbackReason == null) {
        return Realization.DELEGATE_BACKTRACK;
      }
      if (allowJdkFallback) {
        return Realization.DELEGATE_FALLBACK;
      }
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' requires java.util.regex fallback (strategy "
              + result.strategy
              + "): "
              + backtrackFallbackReason
              + ". Add options = ReggieOption.ALLOW_JDK_FALLBACK to @RegexPattern to permit a"
              + " delegating stub, or use Reggie.compile() at runtime.");
    }
    if (needsJdk(ast, result)) {
      if (allowJdkFallback) {
        return Realization.DELEGATE_FALLBACK;
      }
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' requires java.util.regex fallback (strategy "
              + result.strategy
              + "). Add options = ReggieOption.ALLOW_JDK_FALLBACK to @RegexPattern to permit a"
              + " delegating stub, or use Reggie.compile() at runtime.");
    }
    if (hybridPlan(analyzer, ast, nfa, result, groupCount) != null) {
      return Realization.HYBRID;
    }
    // Mirrors RuntimeCompiler step 5.5: the recursive-descent generator does not retry a choice
    // point when the rest of the pattern fails after it, so such a pattern is not generated.
    if (result.strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT
        && !rdOneWay(ast, result)) {
      if (allowJdkFallback) {
        return Realization.DELEGATE_FALLBACK;
      }
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' requires java.util.regex fallback: recursive-descent pattern with choice"
              + " points: "
              + backtrackRefusal
              + ". Add options = ReggieOption.ALLOW_JDK_FALLBACK to @RegexPattern to permit a"
              + " delegating stub, or use Reggie.compile() at runtime.");
    }
    return Realization.NATIVE;
  }

  private static boolean rdOneWay(RegexNode ast, PatternAnalyzer.MatchingStrategyResult result) {
    return result.strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT
        && RecursiveDescentChoiceAnalysis.isOneWay(ast)
        && !needsJdk(ast, result); // needsJdk includes needsFallback(ast, strategy, false)
  }

  private static boolean needsJdk(RegexNode ast, PatternAnalyzer.MatchingStrategyResult result) {
    return result.anchorConditionDiluted
        || result.alternationPriorityConflict
        || result.captureAmbiguous
        || FallbackPatternDetector.needsFallback(ast, result.strategy) != null
        || StrategyJdkClassifier.classifyJdkDependency(result.strategy)
            == StrategyJdkClassifier.StrategyJdkClass.FULL_FALLBACK;
  }

  /**
   * Returns the DFA half for a {@link Realization#HYBRID} pattern, or {@code null} when the pattern
   * is not one. Mirrors {@code RuntimeCompiler}'s hybrid routing for quantified capturing groups
   * ({@code usePosixLastMatch}): the captureless DFA from {@code analyzeAndRecommend(true)}, else
   * the priority-aware retry, else the original analysis's DFA. Patterns whose captureless DFA is
   * anchor-diluted, or for which no DFA exists, stay on their non-hybrid realization, as in {@code
   * RuntimeCompiler}.
   */
  private static HybridPlan hybridPlan(
      PatternAnalyzer analyzer,
      RegexNode ast,
      NFA nfa,
      PatternAnalyzer.MatchingStrategyResult result,
      int groupCount) {
    if (groupCount == 0
        || !result.usePosixLastMatch
        || nfa == null
        || nfa.hasCountedLoops()
        || result.strategy == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE
        || result.strategy == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE
        || needsJdk(ast, result)) {
      return null;
    }
    PatternAnalyzer.MatchingStrategyResult dfaResult = analyzer.analyzeAndRecommend(true);
    NFA lazyNfa = null;
    if (dfaResult.dfa == null) {
      lazyNfa = result.lazyNfa ? new ThompsonBuilder(true).build(ast, groupCount) : null;
      dfaResult = analyzer.analyzeAndRecommendPriorityAware(lazyNfa);
    }
    if (dfaResult.anchorConditionDiluted) {
      return null;
    }
    if (dfaResult.dfa != null) {
      NFA dfaHalfNfa = lazyNfa != null && result.dfa == null ? lazyNfa : nfa;
      return new HybridPlan(dfaResult, dfaHalfNfa, dfaResult.dfa.isLeftmostFirstPruned());
    }
    return result.dfa != null ? new HybridPlan(result, nfa, false) : null;
  }

  /**
   * Generates the two classes of a {@link Realization#HYBRID} pattern: the DFA half (matcher class
   * name + {@link #DFA_HALF_SUFFIX}), which serves the boolean methods, and an {@code
   * OPTIMIZED_NFA} half with POSIX last-match spans (matcher class name + {@link
   * #NFA_HALF_SUFFIX}), which serves the capture methods. Throws {@link IllegalStateException} if
   * the pattern is not realized as {@code HYBRID}.
   */
  public HybridClasses generateHybrid() throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> nameMap = parser.getGroupNameMap();
    int groupCount = GroupCounter.count(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, groupCount);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
    this.resolvedStrategy = result.strategy;
    HybridPlan plan = hybridPlan(analyzer, ast, nfa, result, groupCount);
    if (plan == null) {
      throw new IllegalStateException("Pattern '" + pattern + "' is not realized as HYBRID");
    }
    PatternAnalyzer.MatchingStrategyResult nfaHalf =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA,
            null,
            null,
            false,
            result.requiredLiterals,
            result.lookaheadGreedyInfo,
            result.usePosixLastMatch);
    return new HybridClasses(
        emit(className + DFA_HALF_SUFFIX, plan.dfaHalf(), plan.dfaHalfNfa(), ast, nameMap),
        emit(className + NFA_HALF_SUFFIX, nfaHalf, nfa, ast, nameMap),
        plan.lazyFind());
  }

  /**
   * Returns the group-name map for the resolved pattern. Re-parses the pattern to extract named
   * groups.
   */
  public Map<String, Integer> resolvedNameMap() throws Exception {
    RegexParser parser = new RegexParser();
    parser.parse(pattern);
    return parser.getGroupNameMap();
  }

  /**
   * Generate the complete bytecode for the matcher class. Pipeline: Pattern → Parser → AST →
   * Thompson NFA → Strategy → Bytecode
   */
  public byte[] generate() throws Exception {
    // 1. Parse pattern to AST
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> nameMap = parser.getGroupNameMap();

    // 2. Build NFA using Thompson construction (greedy, lazyAware=false).
    // This greedy NFA is used by PatternAnalyzer for strategy detection and by DFA/NFA-based
    // bytecode generators. It is intentionally built before strategy selection because
    // PatternAnalyzer needs an NFA regardless of strategy. For PIKEVM_CAPTURE patterns the greedy
    // NFA is never used for matching — the throw at line 207 exits before any code generation —
    // but building it here is simpler than deferring construction until after
    // analyzeAndRecommend().
    // A context-free pattern (subroutine, conditional, branch reset) gets no NFA, as in
    // resolveRealization.
    // Count groups in pattern for group tracking
    int groupCount = GroupCounter.count(pattern);
    NFA nfa =
        PatternAnalyzer.requiresRecursiveDescent(ast)
            ? null
            : new ThompsonBuilder().build(ast, groupCount);
    if (nfa != null && nfa.hasCountedLoops()) {
      // See resolveRealization: only the runtime pipeline executes counted-loop markers.
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' uses a bounded quantifier over the unrolling budget; the counted-loop engine is"
              + " runtime-only. Use Reggie.compile() instead.");
    }

    // 3. Analyze and select strategy
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
    PatternAnalyzer.MatchingStrategy strategy = result.strategy;
    this.resolvedStrategy = strategy;

    // Reject patterns with known engine bugs at compile time — emitting buggy bytecode is worse
    // than a build failure. Use Reggie.compile() for runtime compilation with automatic fallback.
    if (result.anchorConditionDiluted) {
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' cannot be compiled at annotation-processing time: anchor condition diluted in"
              + " DFA construction. Use Reggie.compile() for runtime compilation with automatic"
              + " fallback.");
    }
    if (result.alternationPriorityConflict) {
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' cannot be compiled at annotation-processing time: alternation priority"
              + " conflict — DFA longest-match vs NFA first-alternative semantics. Use"
              + " Reggie.compile() for runtime compilation with automatic fallback.");
    }
    if (result.captureAmbiguous) {
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' cannot be compiled at annotation-processing time: capture-ambiguous group"
              + " bindings — the DFA cannot determine the correct group spans. Use"
              + " Reggie.compile() for runtime compilation with automatic fallback.");
    }
    if (strategy == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE
        || strategy == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE) {
      // PIKEVM_CAPTURE (and BITSTATE_CAPTURE, which folds into the same DELEGATE_PIKEVM
      // realization — see resolveRealization) requires a lazy-aware NFA for correct
      // shortest-match semantics. Bytecode generation is not supported at annotation-processing
      // time; route to runtime. (No NFA rebuild needed here — the result is discarded immediately
      // by the throw below.)
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' cannot be compiled at annotation-processing time: "
              + strategy
              + " requires a PikeVMMatcher instance at runtime. Use Reggie.compile() instead.");
    }
    String fallbackReason = FallbackPatternDetector.needsFallback(ast, strategy);
    if (fallbackReason != null) {
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' cannot be compiled at annotation-processing time: "
              + fallbackReason
              + ". Use Reggie.compile() for runtime compilation with automatic fallback.");
    }

    // Reject FULL_FALLBACK strategies at compile time. The runtime path routes these to
    // JavaRegexFallbackMatcher (which is correct), but @RegexPattern generates a fixed class that
    // cannot fall back at runtime, so emitting the native bytecode here would ship a
    // known-incorrect
    // matcher (e.g. the lookahead boolean engine is wrong). Fail the build instead.
    if (StrategyJdkClassifier.classifyJdkDependency(strategy)
        == StrategyJdkClassifier.StrategyJdkClass.FULL_FALLBACK) {
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' cannot be compiled at annotation-processing time: it requires java.util.regex"
              + " fallback (strategy "
              + strategy
              + "). Use Reggie.compile() for runtime compilation with automatic fallback.");
    }

    // Quantified capturing groups need POSIX last-match spans, which the tagged DFA does not
    // track; resolveRealization routes them to HYBRID (see generateHybrid).
    if (hybridPlan(analyzer, ast, nfa, result, groupCount) != null) {
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' is realized as a DFA/NFA hybrid; use generateHybrid() instead.");
    }

    return emit(className, result, nfa, ast, nameMap);
  }

  /** Emits one matcher class named {@code cls} (internal name) for {@code result}. */
  private byte[] emit(
      String cls,
      PatternAnalyzer.MatchingStrategyResult result,
      NFA nfa,
      RegexNode ast,
      Map<String, Integer> nameMap) {
    PatternAnalyzer.MatchingStrategy strategy = result.strategy;
    DFA dfa = result.dfa;
    boolean caseInsensitive = isCaseInsensitive(pattern);

    // 4. Generate bytecode based on strategy
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    // Class declaration — LAZY_DFA generated class implements NfaStep directly so the cache
    // can call nfaStep() without INVOKEDYNAMIC (which is unsupported in hidden classes).
    String[] ifaces =
        switch (strategy) {
          case LAZY_DFA -> new String[] {"com/datadoghq/reggie/runtime/NfaStep"};
          // linStep/linStartAccepts for DFALinearFind (emitted with matchesAtStart)
          case DFA_UNROLLED,
              DFA_UNROLLED_WITH_ASSERTIONS,
              DFA_UNROLLED_WITH_GROUPS,
              DFA_SWITCH,
              DFA_SWITCH_WITH_ASSERTIONS,
              DFA_SWITCH_WITH_GROUPS ->
              new String[] {"com/datadoghq/reggie/runtime/DFALinearFind$Stepper"};
          default -> null;
        };
    cw.visit(
        V21,
        ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
        cls,
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        ifaces);

    // Generate constructor (with NFA state initialization for NFA-based strategies)
    boolean needsNFAState =
        strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND
            || strategy == PatternAnalyzer.MatchingStrategy.HYBRID_DFA_LOOKAHEAD
            || strategy == PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTIPLE_LOOKAHEADS
            || strategy == PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS
            || strategy == PatternAnalyzer.MatchingStrategy.LAZY_DFA;
    boolean needsRecursiveDescent = strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT;
    boolean nativeRichApi =
        StrategyJdkClassifier.classifyJdkDependency(strategy)
            == StrategyJdkClassifier.StrategyJdkClass.NATIVE;
    generateConstructor(cw, needsNFAState, needsRecursiveDescent, nfa, nameMap, nativeRichApi);

    // Generate methods based on strategy
    switch (strategy) {
      case STATELESS_LOOP:
        PatternAnalyzer.StatelessPatternInfo statelessInfo =
            (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;
        StatelessLoopBytecodeGenerator statelessGen =
            new StatelessLoopBytecodeGenerator(statelessInfo);
        statelessGen.generateHelperMethods(cw, cls);
        statelessGen.generateMatchesMethod(cw, cls);
        statelessGen.generateFindMethod(cw, cls);
        statelessGen.generateFindFromMethod(cw, cls);
        statelessGen.generateFindBoundsFromMethod(cw, cls);
        statelessGen.generateMatchMethod(cw, cls);
        statelessGen.generateMatchesBoundedMethod(cw, cls);
        statelessGen.generateMatchBoundedMethod(cw, cls);
        statelessGen.generateFindMatchMethod(cw, cls);
        statelessGen.generateFindMatchFromMethod(cw, cls);
        break;

      case GREEDY_SUFFIX:
        new GreedySuffixBytecodeGenerator((GreedySuffixInfo) result.patternInfo)
            .generateAll(cw, cls);
        break;

      case SPECIALIZED_GREEDY_CHARCLASS:
        PatternAnalyzer.GreedyCharClassInfo greedyInfo =
            (PatternAnalyzer.GreedyCharClassInfo) result.patternInfo;
        GreedyCharClassBytecodeGenerator greedyGen =
            new GreedyCharClassBytecodeGenerator(greedyInfo, nfa.getGroupCount());
        greedyGen.generateMatchesMethod(cw, cls);
        greedyGen.generateFindMethod(cw, cls);
        greedyGen.generateFindFromMethod(cw, cls);
        greedyGen.generateMatchMethod(cw, cls);
        greedyGen.generateMatchesBoundedMethod(cw, cls);
        greedyGen.generateFindMatchMethod(cw, cls);
        greedyGen.generateFindMatchFromMethod(cw, cls);
        greedyGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case SPECIALIZED_MULTI_GROUP_GREEDY:
        PatternAnalyzer.MultiGroupGreedyInfo multiGroupInfo =
            (PatternAnalyzer.MultiGroupGreedyInfo) result.patternInfo;
        MultiGroupGreedyBytecodeGenerator multiGroupGen =
            new MultiGroupGreedyBytecodeGenerator(multiGroupInfo, nfa.getGroupCount());
        multiGroupGen.generateMatchesMethod(cw, cls);
        multiGroupGen.generateFindMethod(cw, cls);
        multiGroupGen.generateFindFromMethod(cw, cls);
        multiGroupGen.generateMatchMethod(cw, cls);
        multiGroupGen.generateMatchesBoundedMethod(cw, cls);
        multiGroupGen.generateFindMatchMethod(cw, cls);
        multiGroupGen.generateFindMatchFromMethod(cw, cls);
        multiGroupGen.generateMatchFromPositionMethod(cw, cls);
        multiGroupGen.generateFindBoundsFromMethod(cw, cls);
        multiGroupGen.generateTryMatchBoundsFromPositionMethod(cw, cls);
        break;

      case BITSTATE_BYTECODE:
        PatternAnalyzer.PrefixGuardedScanInfo prefixGuardedInfo =
            (PatternAnalyzer.PrefixGuardedScanInfo) result.patternInfo;
        BitStateBytecodeGenerator bitStateGen =
            new BitStateBytecodeGenerator(prefixGuardedInfo, nfa.getGroupCount());
        bitStateGen.generateAll(cw, cls);
        break;

      case DETERMINISTIC_CHAIN_BYTECODE:
        PatternAnalyzer.DeterministicChainInfo chainInfo =
            (PatternAnalyzer.DeterministicChainInfo) result.patternInfo;
        DeterministicChainBytecodeGenerator chainGen =
            new DeterministicChainBytecodeGenerator(chainInfo, nfa.getGroupCount());
        chainGen.generateMatchesMethod(cw, cls);
        chainGen.generateMatchMethod(cw, cls);
        chainGen.generateFindMethod(cw, cls);
        chainGen.generateFindFromMethod(cw, cls);
        chainGen.generateFindMatchMethod(cw, cls);
        chainGen.generateFindMatchFromMethod(cw, cls);
        chainGen.generateFindBoundsFromMethod(cw, cls);
        chainGen.generateFallbackSupport(cw, cls);
        break;

      case SPECIALIZED_FIXED_SEQUENCE:
        PatternAnalyzer.FixedSequenceInfo fixedInfo =
            (PatternAnalyzer.FixedSequenceInfo) result.patternInfo;
        FixedSequenceBytecodeGenerator fixedGen =
            new FixedSequenceBytecodeGenerator(fixedInfo, nfa.getGroupCount());
        if (fixedGen.needsBoundaryHelpers()) {
          fixedGen.generateBoundaryHelperMethods(cw, cls);
        }
        fixedGen.generateMatchesMethod(cw, cls);
        fixedGen.generateFindMethod(cw, cls);
        fixedGen.generateFindFromMethod(cw, cls);
        fixedGen.generateMatchMethod(cw, cls);
        fixedGen.generateMatchesBoundedMethod(cw, cls);
        fixedGen.generateFindMatchMethod(cw, cls);
        fixedGen.generateFindMatchFromMethod(cw, cls);
        fixedGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case SPECIALIZED_SUFFIX_SEQUENCE:
        PatternAnalyzer.SuffixSequenceInfo suffixInfo =
            (PatternAnalyzer.SuffixSequenceInfo) result.patternInfo;
        SuffixSequenceBytecodeGenerator suffixGen =
            new SuffixSequenceBytecodeGenerator(suffixInfo, nfa.getGroupCount());
        suffixGen.generateMatchesMethod(cw, cls);
        suffixGen.generateFindMethod(cw, cls);
        suffixGen.generateFindFromMethod(cw, cls);
        suffixGen.generateMatchMethod(cw, cls);
        suffixGen.generateMatchesBoundedMethod(cw, cls);
        suffixGen.generateFindMatchMethod(cw, cls);
        suffixGen.generateFindMatchFromMethod(cw, cls);
        suffixGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case SPECIALIZED_BOUNDED_QUANTIFIERS:
        PatternAnalyzer.BoundedQuantifierInfo boundedInfo =
            (PatternAnalyzer.BoundedQuantifierInfo) result.patternInfo;
        BoundedQuantifierBytecodeGenerator boundedGen =
            new BoundedQuantifierBytecodeGenerator(boundedInfo, nfa.getGroupCount());
        boundedGen.generateMatchesMethod(cw, cls);
        boundedGen.generateFindMethod(cw, cls);
        boundedGen.generateFindFromMethod(cw, cls);
        boundedGen.generateMatchMethod(cw, cls);
        boundedGen.generateMatchesBoundedMethod(cw, cls);
        boundedGen.generateFindMatchMethod(cw, cls);
        boundedGen.generateFindMatchFromMethod(cw, cls);
        boundedGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case DFA_UNROLLED:
      case DFA_UNROLLED_WITH_ASSERTIONS:
      case DFA_UNROLLED_WITH_GROUPS:
        // Fully unrolled DFA for patterns with <50 states
        // NOTE: the NFA must be passed (same as RuntimeCompiler) — the generator derives
        // requiresStartAnchor/hasMultilineStart from it for the findFrom position guard.
        DFAUnrolledBytecodeGenerator unrolledGen =
            new DFAUnrolledBytecodeGenerator(dfa, nfa.getGroupCount(), result.useTaggedDFA, nfa);
        unrolledGen.generateMatchesMethod(cw, cls);
        unrolledGen.generateFindMethod(cw, cls);
        unrolledGen.generateFindFromMethod(cw, cls);
        unrolledGen.generateMatchesAtStartMethod(cw);
        unrolledGen.generateMatchMethod(cw, cls);
        // Private findMatchFromForMatch helper called by tagged match() (same as RuntimeCompiler);
        // no-op for non-tagged DFAs
        unrolledGen.generateFindMatchFromMethodTaggedNoCut(cw, cls);
        unrolledGen.generateMatchesBoundedMethod(cw, cls);
        unrolledGen.generateMatchBoundedMethod(cw, cls);
        unrolledGen.generateFindMatchMethod(cw, cls);
        unrolledGen.generateFindMatchFromMethod(cw, cls);
        // findMatchFrom calls this helper, and findBoundsFrom delegates to it whenever the DFA
        // carries lookaround assertions (same as RuntimeCompiler); without it every
        // findAll/replaceAll/split use throws NoSuchMethodError
        unrolledGen.generateFindLongestMatchEndMethod(cw, cls);
        unrolledGen.generateFindBoundsFromMethod(cw, cls);
        // Static lookup tables for range-heavy charsets referenced by the methods above (same as
        // RuntimeCompiler); must follow every generate*Method. No-op when none were needed.
        unrolledGen.generateLookupTables(cw);
        break;

      case DFA_SWITCH:
      case DFA_SWITCH_WITH_ASSERTIONS:
      case DFA_SWITCH_WITH_GROUPS:
        // Switch-based DFA for patterns with 50-300 states
        // NOTE: the NFA must be passed (same as RuntimeCompiler). DFA_SWITCH emits no
        // per-transition anchor guards (its emitTransitionEntryGuard is dead code), so the
        // nfa-derived requiresStartAnchor/hasMultilineStart findFrom position guard is the
        // ONLY enforcement of a leading ^/\A/(multiline) ^ — omitting the NFA made generated
        // find() match mid-input on anchored patterns.
        DFASwitchBytecodeGenerator switchGen =
            new DFASwitchBytecodeGenerator(dfa, nfa.getGroupCount(), nfa);
        switchGen.generateMatchesMethod(cw, cls);
        switchGen.generateFindMethod(cw, cls);
        switchGen.generateFindFromMethod(cw, cls);
        switchGen.generateMatchesAtStartMethod(cw);
        switchGen.generateMatchMethod(cw, cls);
        switchGen.generateMatchIntoMethod(cw, cls);
        switchGen.generateMatchesBoundedMethod(cw, cls);
        switchGen.generateFindMatchMethod(cw, cls);
        switchGen.generateFindMatchFromMethod(cw, cls);
        switchGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case DFA_TABLE:
        DFATableBytecodeGenerator tableGen = new DFATableBytecodeGenerator(dfa);
        tableGen.generateStaticData(cw, cls);
        tableGen.generateMatchesMethod(cw, cls);
        tableGen.generateFindMethod(cw, cls);
        tableGen.generateFindFromMethod(cw, cls);
        tableGen.generateMatchMethod(cw, cls);
        tableGen.generateMatchIntoMethod(cw, cls);
        tableGen.generateMatchesBoundedMethod(cw, cls);
        tableGen.generateMatchBoundedMethod(cw, cls);
        tableGen.generateFindMatchMethod(cw, cls);
        tableGen.generateFindMatchFromMethod(cw, cls);
        tableGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case BITPARALLEL_GLUSHKOV:
        BitParallelGlushkovBytecodeGenerator glushkovGen =
            new BitParallelGlushkovBytecodeGenerator(GlushkovAutomaton.from(nfa));
        glushkovGen.generateStaticData(cw, cls);
        glushkovGen.generateMatchesMethod(cw, cls);
        glushkovGen.generateFindMethod(cw, cls);
        glushkovGen.generateFindFromMethod(cw, cls);
        glushkovGen.generateMatchMethod(cw, cls);
        glushkovGen.generateMatchIntoMethod(cw, cls);
        glushkovGen.generateMatchesBoundedMethod(cw, cls);
        glushkovGen.generateMatchBoundedMethod(cw, cls);
        glushkovGen.generateFindMatchMethod(cw, cls);
        glushkovGen.generateFindMatchFromMethod(cw, cls);
        glushkovGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case COUNTING_GLUSHKOV:
        PatternAnalyzer.CountingGlushkovInfo cgInfo =
            (PatternAnalyzer.CountingGlushkovInfo) result.patternInfo;
        CountingGlushkovBytecodeGenerator cgGen =
            new CountingGlushkovBytecodeGenerator(
                cgInfo.base, cgInfo.counterMin, cgInfo.counterMax);
        cgGen.generateStaticData(cw, cls);
        cgGen.generateMatchesMethod(cw, cls);
        cgGen.generateFindMethod(cw, cls);
        cgGen.generateFindFromMethod(cw, cls);
        cgGen.generateMatchMethod(cw, cls);
        cgGen.generateMatchIntoMethod(cw, cls);
        cgGen.generateMatchesBoundedMethod(cw, cls);
        cgGen.generateMatchBoundedMethod(cw, cls);
        cgGen.generateFindMatchMethod(cw, cls);
        cgGen.generateFindMatchFromMethod(cw, cls);
        cgGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case SPECIALIZED_CONCAT_GREEDY_GROUP:
        new ConcatGreedyGroupBytecodeGenerator(
                (ConcatGreedyGroupInfo) result.patternInfo, cls.replace('/', '.'))
            .generate(cw);
        break;

      case SPECIALIZED_QUANTIFIED_GROUP:
        if (result.patternInfo instanceof QuantifiedGroupInfo quantifiedGroupInfo) {
          new QuantifiedGroupBytecodeGenerator(quantifiedGroupInfo, cls.replace('/', '.'))
              .generate(cw);
        } else {
          new ConcatQuantifiedGroupsBytecodeGenerator(
                  (ConcatQuantifiedGroupsInfo) result.patternInfo, cls.replace('/', '.'))
              .generate(cw);
        }
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
                false,
                caseInsensitive);
        hybridGen.generateMatchesMethod(cw, cls);
        hybridGen.generateFindMethod(cw, cls);
        hybridGen.generateFindFromMethod(cw, cls);
        hybridGen.generateMatchMethod(cw, cls);
        hybridGen.generateMatchIntoMethod(cw, cls);
        hybridGen.generateMatchBoundedMethod(cw, cls);
        hybridGen.generateFindMatchMethod(cw, cls);
        hybridGen.generateFindMatchFromMethod(cw, cls);
        // No generateFindLongestMatchEndMethod: hybrid NFAs always carry lookaround assertions,
        // and the shared findBoundsFrom routes assertion-bearing NFAs to findMatchFrom, so the
        // helper would be unreferenced dead bytecode.
        hybridGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case OPTIMIZED_NFA:
        // Generic optimized NFA fallback
        NFABytecodeGenerator plainNfaGen =
            new NFABytecodeGenerator(
                nfa,
                null,
                null,
                result.requiredLiterals,
                result.lookaheadGreedyInfo,
                result.usePosixLastMatch,
                caseInsensitive);
        plainNfaGen.generateMatchesMethod(cw, cls);
        plainNfaGen.generateFindMethod(cw, cls);
        plainNfaGen.generateFindFromMethod(cw, cls);
        plainNfaGen.generateMatchMethod(cw, cls);
        plainNfaGen.generateMatchIntoMethod(cw, cls);
        plainNfaGen.generateMatchBoundedMethod(cw, cls);
        plainNfaGen.generateFindMatchMethod(cw, cls);
        plainNfaGen.generateFindMatchFromMethod(cw, cls);
        // Parity with RuntimeCompiler: findBoundsFrom calls the helper, so a missing emission
        // would be NoSuchMethodError on every findAll/replaceAll/split.
        plainNfaGen.generateFindLongestMatchEndMethod(cw, cls);
        plainNfaGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case LINEAR_BACKREFERENCE:
        LinearPatternInfo linearInfo = (LinearPatternInfo) result.patternInfo;
        LinearPatternBytecodeGenerator linearGen =
            new LinearPatternBytecodeGenerator(linearInfo, caseInsensitive);
        linearGen.generateMatchesMethod(cw, cls);
        linearGen.generateFindMethod(cw, cls);
        linearGen.generateFindFromMethod(cw, cls);
        linearGen.generateMatchMethod(cw, cls);
        linearGen.generateMatchesBoundedMethod(cw, cls);
        linearGen.generateMatchBoundedMethod(cw, cls);
        linearGen.generateFindMatchMethod(cw, cls);
        linearGen.generateFindMatchFromMethod(cw, cls);
        linearGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case SPECIALIZED_BACKREFERENCE:
        BackreferencePatternInfo backrefInfo = (BackreferencePatternInfo) result.patternInfo;
        BackreferenceBytecodeGenerator backrefGen = new BackreferenceBytecodeGenerator(backrefInfo);
        backrefGen.generateMatchesMethod(cw, cls);
        backrefGen.generateFindMethod(cw, cls);
        backrefGen.generateFindFromMethod(cw, cls);
        backrefGen.generateMatchMethod(cw, cls);
        backrefGen.generateMatchBoundedMethod(cw, cls);
        backrefGen.generateFindMatchMethod(cw, cls);
        backrefGen.generateFindMatchFromMethod(cw, cls);
        break;

      case OPTIMIZED_NFA_WITH_BACKREFS:
        {
          // Disable the longest-literal indexOf optimization: for NFA-with-backrefs patterns the
          // required literal appears after a variable-length prefix, so indexOf would skip valid
          // start positions and produce false negatives.
          NFABytecodeGenerator nfaGen =
              new NFABytecodeGenerator(
                  nfa,
                  null,
                  null,
                  result.requiredLiterals,
                  result.lookaheadGreedyInfo,
                  false,
                  caseInsensitive,
                  true);
          nfaGen.generateMatchesMethod(cw, cls);
          nfaGen.generateFindMethod(cw, cls);
          nfaGen.generateFindFromMethod(cw, cls);
          nfaGen.generateMatchMethod(cw, cls);
          nfaGen.generateMatchIntoMethod(cw, cls);
          nfaGen.generateMatchBoundedMethod(cw, cls);
          nfaGen.generateMatchesBoundedMethod(cw, cls);
          nfaGen.generateMatchBoundedCharSequenceMethod(cw, cls);
          nfaGen.generateFindMatchMethod(cw, cls);
          nfaGen.generateFindMatchFromMethod(cw, cls);
          // Do not override findBoundsFrom: the base-class default delegates to findMatchFrom,
          // which preserves backref group semantics. findLongestMatchEnd runs without group
          // tracking and would skip backrefCheck states, producing incorrect bounds.
          break;
        }
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
        {
          NFABytecodeGenerator nfaGen =
              new NFABytecodeGenerator(
                  nfa,
                  null,
                  null,
                  result.requiredLiterals,
                  result.lookaheadGreedyInfo,
                  false,
                  caseInsensitive);
          nfaGen.generateMatchesMethod(cw, cls);
          nfaGen.generateFindMethod(cw, cls);
          nfaGen.generateFindFromMethod(cw, cls);
          nfaGen.generateMatchMethod(cw, cls);
          nfaGen.generateMatchIntoMethod(cw, cls);
          nfaGen.generateMatchBoundedMethod(cw, cls);
          nfaGen.generateFindMatchMethod(cw, cls);
          nfaGen.generateFindMatchFromMethod(cw, cls);
          // No generateFindLongestMatchEndMethod: this arm is reached only with lookaround
          // assertions, and the shared findBoundsFrom routes assertion-bearing NFAs to
          // findMatchFrom, so the helper would be unreferenced dead bytecode.
          nfaGen.generateFindBoundsFromMethod(cw, cls);
          break;
        }

      case SPECIALIZED_LITERAL_ALTERNATION:
        PatternAnalyzer.LiteralAlternationInfo literalAltInfo =
            (PatternAnalyzer.LiteralAlternationInfo) result.patternInfo;
        LiteralAlternationTrieGenerator literalAltGen =
            new LiteralAlternationTrieGenerator(literalAltInfo, nfa.getGroupCount());
        literalAltGen.generateMatchesMethod(cw, cls);
        literalAltGen.generateFindMethod(cw, cls);
        literalAltGen.generateFindFromMethod(cw, cls);
        literalAltGen.generateMatchMethod(cw, cls);
        literalAltGen.generateMatchesBoundedMethod(cw, cls);
        literalAltGen.generateMatchBoundedMethod(cw, cls);
        literalAltGen.generateFindMatchMethod(cw, cls);
        literalAltGen.generateFindMatchFromMethod(cw, cls);
        literalAltGen.generateFindBoundsFromMethod(cw, cls);
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
                false,
                caseInsensitive);
        literalGen.generateMatchesMethod(cw, cls);
        literalGen.generateFindMethod(cw, cls);
        literalGen.generateFindFromMethod(cw, cls);
        literalGen.generateMatchMethod(cw, cls);
        literalGen.generateMatchIntoMethod(cw, cls);
        literalGen.generateMatchBoundedMethod(cw, cls);
        literalGen.generateFindMatchMethod(cw, cls);
        literalGen.generateFindMatchFromMethod(cw, cls);
        literalGen.generateFindBoundsFromMethod(cw, cls);
        break;

      case ONEPASS_NFA:
        OnePassBytecodeGenerator onePass = new OnePassBytecodeGenerator(nfa);
        onePass.generateMatchesMethod(cw, cls);
        onePass.generateMatchFromMethod(cw, cls);
        onePass.generateFindMethod(cw, cls);
        onePass.generateFindFromMethod(cw, cls);
        onePass.generateMatchMethod(cw, cls);
        onePass.generateFindMatchMethod(cw, cls);
        onePass.generateFindMatchFromMethod(cw, cls);
        onePass.generateMatchInRangeMethod(cw, cls);
        onePass.generateFindBoundsFromMethod(cw, cls);
        break;

      case RECURSIVE_DESCENT:
        // Context-free patterns: subroutines, conditionals, branch reset, non-greedy
        RecursiveDescentBytecodeGenerator recursiveGen =
            new RecursiveDescentBytecodeGenerator(ast, nfa, rdOneWay(ast, result));

        // Generate parser methods FIRST (parseRoot and AST parsers)
        recursiveGen.generateAllParserMethods(cw, cls);

        // Generate public API methods (these call the parser methods)
        recursiveGen.generateMatchesMethod(cw, cls);
        recursiveGen.generateMatchIntoMethod(cw, cls);
        recursiveGen.generateFindMethod(cw, cls);
        recursiveGen.generateFindFromMethod(cw, cls);
        recursiveGen.generateFindBoundsFromMethod(cw, cls);
        recursiveGen.generateMatchMethod(cw, cls);
        recursiveGen.generateFindMatchMethod(cw, cls);
        recursiveGen.generateFindMatchFromMethod(cw, cls);
        break;

      case VARIABLE_CAPTURE_BACKREF:
        VariableCaptureBackrefInfo varCaptureBackrefInfo =
            (VariableCaptureBackrefInfo) result.patternInfo;
        VariableCaptureBackrefBytecodeGenerator varCaptureGen =
            new VariableCaptureBackrefBytecodeGenerator(varCaptureBackrefInfo, cls);
        varCaptureGen.generate(cw);
        break;

      case OPTIONAL_GROUP_BACKREF:
        OptionalGroupBackrefInfo optGroupBackrefInfo =
            (OptionalGroupBackrefInfo) result.patternInfo;
        OptionalGroupBackrefBytecodeGenerator optGroupGen =
            new OptionalGroupBackrefBytecodeGenerator(optGroupBackrefInfo, cls);
        optGroupGen.generate(cw);
        break;

      case FIXED_REPETITION_BACKREF:
        FixedRepetitionBackrefInfo fixedRepBackrefInfo =
            (FixedRepetitionBackrefInfo) result.patternInfo;
        FixedRepetitionBackrefBytecodeGenerator fixedRepGen =
            new FixedRepetitionBackrefBytecodeGenerator(fixedRepBackrefInfo, cls);
        fixedRepGen.generateMatchesMethod(cw);
        fixedRepGen.generateFindMethod(cw);
        fixedRepGen.generateFindFromMethod(cw);
        fixedRepGen.generateMatchMethod(cw);
        fixedRepGen.generateMatchesBoundedMethod(cw);
        fixedRepGen.generateFindMatchMethod(cw);
        fixedRepGen.generateFindMatchFromMethod(cw);
        break;

      case PINNED_BACKREFERENCE:
        PinnedBackreferenceInfo pinnedBackrefInfo = (PinnedBackreferenceInfo) result.patternInfo;
        PinnedBackreferenceBytecodeGenerator pinnedBackrefGen =
            new PinnedBackreferenceBytecodeGenerator(pinnedBackrefInfo, cls);
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
            new SpecializedOptionalGroupBytecodeGenerator(specOptGroupInfo, cls);
        specOptGroupGen.generateMatchesMethod(cw);
        specOptGroupGen.generateFindMethod(cw);
        specOptGroupGen.generateFindFromMethod(cw);
        specOptGroupGen.generateMatchMethod(cw);
        specOptGroupGen.generateFindMatchMethod(cw);
        specOptGroupGen.generateFindMatchFromMethod(cw);
        break;

      case LAZY_DFA:
        {
          LazyDFABytecodeGenerator lazyGen = new LazyDFABytecodeGenerator(nfa);
          lazyGen.generateStaticFields(cw, cls);
          lazyGen.generateNfaStepMethod(cw, cls);
          lazyGen.generateApplyMethod(cw, cls);
          // Use the delegating (non-inlined) matches() — the AOT class lives in the user's package
          // and cannot access package-private LazyDFACache members that the inlined version uses.
          lazyGen.generateMatchesDelegatingMethod(cw, cls);
          lazyGen.generateMatchMethod(cw, cls);
          // CharSequence abstract methods required by ReggieMatcher.
          lazyGen.generateMatchesBoundedMethod(cw, cls);
          lazyGen.generateMatchBoundedMethod(cw, cls);
          // Remaining methods delegate to the standard NFA generator.
          NFABytecodeGenerator nfaDelegate =
              new NFABytecodeGenerator(
                  nfa,
                  null,
                  null,
                  result.requiredLiterals,
                  result.lookaheadGreedyInfo,
                  false,
                  caseInsensitive);
          // String overload of matchBounded called internally by findMatchFrom/findMatch.
          lazyGen.generateMatchBoundedStringMethod(cw, cls);
          nfaDelegate.generateFindMethod(cw, cls);
          nfaDelegate.generateFindFromMethod(cw, cls);
          nfaDelegate.generateFindLongestMatchEndMethod(cw, cls);
          nfaDelegate.generateFindMatchMethod(cw, cls);
          nfaDelegate.generateFindMatchFromMethod(cw, cls);
          nfaDelegate.generateFindBoundsFromMethod(cw, cls);
          break;
        }

      default:
        throw new IllegalStateException("Unknown strategy: " + strategy);
    }

    cw.visitEnd();
    byte[] bytes = cw.toByteArray();
    return nameMap.isEmpty() ? bytes : injectNamePropagation(bytes);
  }

  /**
   * Post-processes generated bytecode to replace every 4-arg {@code MatchResultImpl} constructor
   * call with the 5-arg variant that also receives {@code this.nameToIndex}, so named-group
   * accessors work on annotation-processor-generated matchers.
   */
  private byte[] injectNamePropagation(byte[] classBytes) {
    ClassReader cr = new ClassReader(classBytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cr.accept(
        new ClassVisitor(ASM9, cw) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(
                ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
              @Override
              public void visitMethodInsn(
                  int opcode, String owner, String mname, String mdescriptor, boolean isInterface) {
                if (opcode == INVOKESPECIAL
                    && "com/datadoghq/reggie/runtime/MatchResultImpl".equals(owner)
                    && "<init>".equals(mname)
                    && "(Ljava/lang/String;[I[II)V".equals(mdescriptor)) {
                  visitVarInsn(ALOAD, 0);
                  visitFieldInsn(
                      GETFIELD,
                      "com/datadoghq/reggie/runtime/ReggieMatcher",
                      "nameToIndex",
                      "Ljava/util/Map;");
                  super.visitMethodInsn(
                      opcode,
                      owner,
                      mname,
                      "(Ljava/lang/String;[I[IILjava/util/Map;)V",
                      isInterface);
                } else {
                  super.visitMethodInsn(opcode, owner, mname, mdescriptor, isInterface);
                }
              }
            };
          }
        },
        0);
    return cw.toByteArray();
  }

  private boolean isCaseInsensitive(String pattern) {
    if (pattern == null || pattern.length() < 4) {
      return false;
    }
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

  /**
   * Generate constructor: public XxxMatcher() { super(pattern); } Optionally initializes NFA state
   * for NFA-based strategies.
   */
  private void generateConstructor(
      ClassWriter cw,
      boolean needsNFAState,
      boolean needsRecursiveDescent,
      NFA nfa,
      Map<String, Integer> nameMap,
      boolean nativeRichApi) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();

    // Load 'this'
    mv.visitVarInsn(ALOAD, 0);

    // Load pattern string
    mv.visitLdcInsn(pattern);

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
      mv.visitLdcInsn(
          nfa != null ? nfa.getGroupCount() : GroupCounter.count(pattern)); // groupCount
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "initRecursiveState",
          "(I)V",
          false);
    }

    // Mark NATIVE-classified matchers so the ReggieMatcher backstop can detect a generator/
    // classification drift if they ever build the java.util.regex rich-API delegate.
    if (nativeRichApi) {
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "markNativeRichApi",
          "()V",
          false);
    }

    if (!nameMap.isEmpty()) {
      // Build a HashMap with the named-group entries and call this.setNameToIndex(unmodifiableMap)
      mv.visitTypeInsn(NEW, "java/util/HashMap");
      mv.visitInsn(DUP);
      mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
      mv.visitVarInsn(ASTORE, 1);
      for (Map.Entry<String, Integer> entry : nameMap.entrySet()) {
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(entry.getKey());
        mv.visitLdcInsn(entry.getValue());
        mv.visitMethodInsn(
            INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/util/HashMap",
            "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(POP);
      }
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "java/util/Collections",
          "unmodifiableMap",
          "(Ljava/util/Map;)Ljava/util/Map;",
          false);
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "setNameToIndex",
          "(Ljava/util/Map;)V",
          false);
    }

    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); // Computed automatically
    mv.visitEnd();
  }
}
