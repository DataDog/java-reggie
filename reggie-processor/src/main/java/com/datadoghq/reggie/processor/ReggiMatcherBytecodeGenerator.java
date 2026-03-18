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
import com.datadoghq.reggie.codegen.analysis.FixedRepetitionBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo;
import com.datadoghq.reggie.codegen.analysis.NestedQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.VariableCaptureBackrefInfo;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.codegen.BackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.BoundedQuantifierBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.DFASwitchBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.DFAUnrolledBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedRepetitionBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.FixedSequenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.GreedyCharClassBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LinearPatternBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.LiteralAlternationTrieGenerator;
import com.datadoghq.reggie.codegen.codegen.MultiGroupGreedyBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.NFABytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.NestedQuantifiedGroupsBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.OnePassBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.OptionalGroupBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.RecursiveDescentBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.StatelessLoopBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.VariableCaptureBackrefBytecodeGenerator;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.objectweb.asm.*;

/**
 * Generates bytecode for ReggieMatcher subclasses using ASM. Uses DFA/NFA pipeline: Pattern → AST →
 * NFA → Strategy → Bytecode.
 */
public class ReggiMatcherBytecodeGenerator {

  private final String className;
  private final String pattern;

  public ReggiMatcherBytecodeGenerator(String packageName, String className, String pattern) {
    this.className = packageName.replace('.', '/') + "/" + className;
    this.pattern = pattern;
  }

  /**
   * Generate the complete bytecode for the matcher class. Pipeline: Pattern → Parser → AST →
   * Thompson NFA → Strategy → Bytecode
   */
  public byte[] generate() throws Exception {
    // 1. Parse pattern to AST
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);

    // 2. Build NFA using Thompson construction
    ThompsonBuilder nfaBuilder = new ThompsonBuilder();
    // Count groups in pattern for group tracking
    int groupCount = countGroups(pattern);
    NFA nfa = nfaBuilder.build(ast, groupCount);

    // Detect case-insensitive mode
    boolean caseInsensitive = isCaseInsensitive(pattern);

    // 3. Analyze and select strategy
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
    PatternAnalyzer.MatchingStrategy strategy = result.strategy;
    DFA dfa = result.dfa;

    // 4. Generate bytecode based on strategy
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    // Class declaration
    cw.visit(
        V21,
        ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
        className,
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        null);

    // Generate constructor (with NFA state initialization for NFA-based strategies)
    boolean needsNFAState =
        strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND
            || strategy == PatternAnalyzer.MatchingStrategy.HYBRID_DFA_LOOKAHEAD;
    generateConstructor(cw, needsNFAState, nfa);

    // Generate methods based on strategy
    switch (strategy) {
      case STATELESS_LOOP:
        PatternAnalyzer.StatelessPatternInfo statelessInfo =
            (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;
        StatelessLoopBytecodeGenerator statelessGen =
            new StatelessLoopBytecodeGenerator(statelessInfo);
        statelessGen.generateHelperMethods(cw, getJavaClassName());
        statelessGen.generateMatchesMethod(cw, getJavaClassName());
        statelessGen.generateFindMethod(cw, getJavaClassName());
        statelessGen.generateFindFromMethod(cw, getJavaClassName());
        statelessGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        statelessGen.generateMatchMethod(cw, getJavaClassName());
        statelessGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        statelessGen.generateMatchBoundedMethod(cw, getJavaClassName());
        statelessGen.generateFindMatchMethod(cw, getJavaClassName());
        statelessGen.generateFindMatchFromMethod(cw, getJavaClassName());
        break;

      case SPECIALIZED_GREEDY_CHARCLASS:
        PatternAnalyzer.GreedyCharClassInfo greedyInfo =
            (PatternAnalyzer.GreedyCharClassInfo) result.patternInfo;
        GreedyCharClassBytecodeGenerator greedyGen =
            new GreedyCharClassBytecodeGenerator(greedyInfo, nfa.getGroupCount());
        greedyGen.generateMatchesMethod(cw, getJavaClassName());
        greedyGen.generateFindMethod(cw, getJavaClassName());
        greedyGen.generateFindFromMethod(cw, getJavaClassName());
        greedyGen.generateMatchMethod(cw, getJavaClassName());
        greedyGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        greedyGen.generateMatchBoundedMethod(cw, getJavaClassName());
        greedyGen.generateFindMatchMethod(cw, getJavaClassName());
        greedyGen.generateFindMatchFromMethod(cw, getJavaClassName());
        greedyGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case SPECIALIZED_MULTI_GROUP_GREEDY:
        PatternAnalyzer.MultiGroupGreedyInfo multiGroupInfo =
            (PatternAnalyzer.MultiGroupGreedyInfo) result.patternInfo;
        MultiGroupGreedyBytecodeGenerator multiGroupGen =
            new MultiGroupGreedyBytecodeGenerator(multiGroupInfo, nfa.getGroupCount());
        multiGroupGen.generateMatchesMethod(cw, getJavaClassName());
        multiGroupGen.generateFindMethod(cw, getJavaClassName());
        multiGroupGen.generateFindFromMethod(cw, getJavaClassName());
        multiGroupGen.generateMatchMethod(cw, getJavaClassName());
        multiGroupGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        multiGroupGen.generateMatchBoundedMethod(cw, getJavaClassName());
        multiGroupGen.generateFindMatchMethod(cw, getJavaClassName());
        multiGroupGen.generateFindMatchFromMethod(cw, getJavaClassName());
        multiGroupGen.generateMatchFromPositionMethod(cw, getJavaClassName());
        multiGroupGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        multiGroupGen.generateTryMatchBoundsFromPositionMethod(cw, getJavaClassName());
        break;

      case SPECIALIZED_FIXED_SEQUENCE:
        PatternAnalyzer.FixedSequenceInfo fixedInfo =
            (PatternAnalyzer.FixedSequenceInfo) result.patternInfo;
        FixedSequenceBytecodeGenerator fixedGen =
            new FixedSequenceBytecodeGenerator(fixedInfo, nfa.getGroupCount());
        fixedGen.generateMatchesMethod(cw, getJavaClassName());
        fixedGen.generateFindMethod(cw, getJavaClassName());
        fixedGen.generateFindFromMethod(cw, getJavaClassName());
        fixedGen.generateMatchMethod(cw, getJavaClassName());
        fixedGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        fixedGen.generateMatchBoundedMethod(cw, getJavaClassName());
        fixedGen.generateFindMatchMethod(cw, getJavaClassName());
        fixedGen.generateFindMatchFromMethod(cw, getJavaClassName());
        fixedGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case SPECIALIZED_BOUNDED_QUANTIFIERS:
        PatternAnalyzer.BoundedQuantifierInfo boundedInfo =
            (PatternAnalyzer.BoundedQuantifierInfo) result.patternInfo;
        BoundedQuantifierBytecodeGenerator boundedGen =
            new BoundedQuantifierBytecodeGenerator(boundedInfo, nfa.getGroupCount());
        boundedGen.generateMatchesMethod(cw, getJavaClassName());
        boundedGen.generateFindMethod(cw, getJavaClassName());
        boundedGen.generateFindFromMethod(cw, getJavaClassName());
        boundedGen.generateMatchMethod(cw, getJavaClassName());
        boundedGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        boundedGen.generateMatchBoundedMethod(cw, getJavaClassName());
        boundedGen.generateFindMatchMethod(cw, getJavaClassName());
        boundedGen.generateFindMatchFromMethod(cw, getJavaClassName());
        boundedGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case DFA_UNROLLED:
      case DFA_UNROLLED_WITH_ASSERTIONS:
      case DFA_UNROLLED_WITH_GROUPS:
        // Fully unrolled DFA for patterns with <50 states
        DFAUnrolledBytecodeGenerator unrolledGen =
            new DFAUnrolledBytecodeGenerator(dfa, nfa.getGroupCount(), result.useTaggedDFA);
        unrolledGen.generateMatchesMethod(cw, getJavaClassName());
        unrolledGen.generateFindMethod(cw, getJavaClassName());
        unrolledGen.generateFindFromMethod(cw, getJavaClassName());
        unrolledGen.generateMatchesAtStartMethod(cw);
        unrolledGen.generateMatchMethod(cw, getJavaClassName());
        unrolledGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        unrolledGen.generateMatchBoundedMethod(cw, getJavaClassName());
        unrolledGen.generateFindMatchMethod(cw, getJavaClassName());
        unrolledGen.generateFindMatchFromMethod(cw, getJavaClassName());
        unrolledGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case DFA_SWITCH:
      case DFA_SWITCH_WITH_ASSERTIONS:
      case DFA_SWITCH_WITH_GROUPS:
        // Switch-based DFA for patterns with 50-300 states
        DFASwitchBytecodeGenerator switchGen =
            new DFASwitchBytecodeGenerator(dfa, nfa.getGroupCount());
        switchGen.generateMatchesMethod(cw, getJavaClassName());
        switchGen.generateFindMethod(cw, getJavaClassName());
        switchGen.generateFindFromMethod(cw, getJavaClassName());
        switchGen.generateMatchesAtStartMethod(cw);
        switchGen.generateMatchMethod(cw, getJavaClassName());
        switchGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        switchGen.generateMatchBoundedMethod(cw, getJavaClassName());
        switchGen.generateFindMatchMethod(cw, getJavaClassName());
        switchGen.generateFindMatchFromMethod(cw, getJavaClassName());
        switchGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case DFA_TABLE:
        // TODO: Implement table-driven DFA generator
        throw new UnsupportedOperationException(
            "DFA_TABLE bytecode generation not yet implemented.");

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
        hybridGen.generateMatchesMethod(cw, getJavaClassName());
        hybridGen.generateFindMethod(cw, getJavaClassName());
        hybridGen.generateFindFromMethod(cw, getJavaClassName());
        hybridGen.generateMatchMethod(cw, getJavaClassName());
        hybridGen.generateMatchBoundedMethod(cw, getJavaClassName());
        hybridGen.generateFindMatchMethod(cw, getJavaClassName());
        hybridGen.generateFindMatchFromMethod(cw, getJavaClassName());
        hybridGen.generateFindBoundsFromMethod(cw, getJavaClassName());
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
                false,
                caseInsensitive);
        plainNfaGen.generateMatchesMethod(cw, getJavaClassName());
        plainNfaGen.generateFindMethod(cw, getJavaClassName());
        plainNfaGen.generateFindFromMethod(cw, getJavaClassName());
        plainNfaGen.generateMatchMethod(cw, getJavaClassName());
        plainNfaGen.generateMatchBoundedMethod(cw, getJavaClassName());
        plainNfaGen.generateFindMatchMethod(cw, getJavaClassName());
        plainNfaGen.generateFindMatchFromMethod(cw, getJavaClassName());
        plainNfaGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case LINEAR_BACKREFERENCE:
        LinearPatternInfo linearInfo = (LinearPatternInfo) result.patternInfo;
        LinearPatternBytecodeGenerator linearGen = new LinearPatternBytecodeGenerator(linearInfo);
        linearGen.generateMatchesMethod(cw, getJavaClassName());
        linearGen.generateFindMethod(cw, getJavaClassName());
        linearGen.generateFindFromMethod(cw, getJavaClassName());
        // Note: LINEAR_BACKREFERENCE currently only supports allocation-free methods
        // Group extraction methods (match, matchBounded, findMatch, findMatchFrom) not yet
        // implemented
        break;

      case SPECIALIZED_BACKREFERENCE:
        BackreferencePatternInfo backrefInfo = (BackreferencePatternInfo) result.patternInfo;
        BackreferenceBytecodeGenerator backrefGen = new BackreferenceBytecodeGenerator(backrefInfo);
        backrefGen.generateMatchesMethod(cw, getJavaClassName());
        backrefGen.generateFindMethod(cw, getJavaClassName());
        backrefGen.generateFindFromMethod(cw, getJavaClassName());
        backrefGen.generateMatchMethod(cw, getJavaClassName());
        backrefGen.generateMatchBoundedMethod(cw, getJavaClassName());
        backrefGen.generateFindMatchMethod(cw, getJavaClassName());
        backrefGen.generateFindMatchFromMethod(cw, getJavaClassName());
        break;

      case OPTIMIZED_NFA_WITH_BACKREFS:
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
        // Fall back to NFA for complex patterns
        NFABytecodeGenerator nfaGen =
            new NFABytecodeGenerator(
                nfa,
                null,
                null,
                result.requiredLiterals,
                result.lookaheadGreedyInfo,
                false,
                caseInsensitive);
        nfaGen.generateMatchesMethod(cw, getJavaClassName());
        nfaGen.generateFindMethod(cw, getJavaClassName());
        nfaGen.generateFindFromMethod(cw, getJavaClassName());
        nfaGen.generateMatchMethod(cw, getJavaClassName());
        nfaGen.generateMatchBoundedMethod(cw, getJavaClassName());
        nfaGen.generateFindMatchMethod(cw, getJavaClassName());
        nfaGen.generateFindMatchFromMethod(cw, getJavaClassName());
        nfaGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case SPECIALIZED_LITERAL_ALTERNATION:
        PatternAnalyzer.LiteralAlternationInfo literalAltInfo =
            (PatternAnalyzer.LiteralAlternationInfo) result.patternInfo;
        LiteralAlternationTrieGenerator literalAltGen =
            new LiteralAlternationTrieGenerator(literalAltInfo, nfa.getGroupCount());
        literalAltGen.generateMatchesMethod(cw, getJavaClassName());
        literalAltGen.generateFindMethod(cw, getJavaClassName());
        literalAltGen.generateFindFromMethod(cw, getJavaClassName());
        literalAltGen.generateMatchMethod(cw, getJavaClassName());
        literalAltGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        literalAltGen.generateMatchBoundedMethod(cw, getJavaClassName());
        literalAltGen.generateFindMatchMethod(cw, getJavaClassName());
        literalAltGen.generateFindMatchFromMethod(cw, getJavaClassName());
        literalAltGen.generateFindBoundsFromMethod(cw, getJavaClassName());
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
        literalGen.generateMatchesMethod(cw, getJavaClassName());
        literalGen.generateFindMethod(cw, getJavaClassName());
        literalGen.generateFindFromMethod(cw, getJavaClassName());
        literalGen.generateMatchMethod(cw, getJavaClassName());
        literalGen.generateMatchBoundedMethod(cw, getJavaClassName());
        literalGen.generateFindMatchMethod(cw, getJavaClassName());
        literalGen.generateFindMatchFromMethod(cw, getJavaClassName());
        literalGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case ONEPASS_NFA:
        OnePassBytecodeGenerator onePass = new OnePassBytecodeGenerator(nfa);
        onePass.generateMatchesMethod(cw, getJavaClassName());
        onePass.generateFindMethod(cw, getJavaClassName());
        onePass.generateFindFromMethod(cw, getJavaClassName());
        onePass.generateMatchMethod(cw, getJavaClassName());
        onePass.generateFindMatchMethod(cw, getJavaClassName());
        onePass.generateFindMatchFromMethod(cw, getJavaClassName());
        onePass.generateMatchesInRangeMethod(cw, getJavaClassName());
        onePass.generateMatchInRangeMethod(cw, getJavaClassName());
        onePass.generateFindBoundsFromMethod(cw, getJavaClassName());
        break;

      case RECURSIVE_DESCENT:
        // Context-free patterns: subroutines, conditionals, branch reset, non-greedy
        RecursiveDescentBytecodeGenerator recursiveGen =
            new RecursiveDescentBytecodeGenerator(ast, nfa);

        // Generate parser methods FIRST (parseRoot and AST parsers)
        recursiveGen.generateAllParserMethods(cw, getJavaClassName());

        // Generate public API methods (these call the parser methods)
        recursiveGen.generateMatchesMethod(cw, getJavaClassName());
        recursiveGen.generateFindMethod(cw, getJavaClassName());
        recursiveGen.generateFindFromMethod(cw, getJavaClassName());
        recursiveGen.generateFindBoundsFromMethod(cw, getJavaClassName());
        recursiveGen.generateMatchesBoundedMethod(cw, getJavaClassName());
        recursiveGen.generateMatchMethod(cw, getJavaClassName());
        recursiveGen.generateFindMatchMethod(cw, getJavaClassName());
        recursiveGen.generateFindMatchFromMethod(cw, getJavaClassName());
        break;

      case VARIABLE_CAPTURE_BACKREF:
        VariableCaptureBackrefInfo varCaptureBackrefInfo =
            (VariableCaptureBackrefInfo) result.patternInfo;
        VariableCaptureBackrefBytecodeGenerator varCaptureGen =
            new VariableCaptureBackrefBytecodeGenerator(varCaptureBackrefInfo, getJavaClassName());
        varCaptureGen.generate(cw);
        break;

      case OPTIONAL_GROUP_BACKREF:
        OptionalGroupBackrefInfo optGroupBackrefInfo =
            (OptionalGroupBackrefInfo) result.patternInfo;
        OptionalGroupBackrefBytecodeGenerator optGroupGen =
            new OptionalGroupBackrefBytecodeGenerator(optGroupBackrefInfo, getJavaClassName());
        optGroupGen.generate(cw);
        break;

      case FIXED_REPETITION_BACKREF:
        FixedRepetitionBackrefInfo fixedRepBackrefInfo =
            (FixedRepetitionBackrefInfo) result.patternInfo;
        FixedRepetitionBackrefBytecodeGenerator fixedRepGen =
            new FixedRepetitionBackrefBytecodeGenerator(fixedRepBackrefInfo, getJavaClassName());
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

      case NESTED_QUANTIFIED_GROUPS:
        NestedQuantifiedGroupsInfo nestedInfo = (NestedQuantifiedGroupsInfo) result.patternInfo;
        NestedQuantifiedGroupsBytecodeGenerator nestedGen =
            new NestedQuantifiedGroupsBytecodeGenerator(nestedInfo, getJavaClassName());
        nestedGen.generate(cw);
        break;

      default:
        throw new IllegalStateException("Unknown strategy: " + strategy);
    }

    cw.visitEnd();
    return cw.toByteArray();
  }

  private String getJavaClassName() {
    // Return internal format (with slashes) as expected by bytecode generators
    return className;
  }

  private int countGroups(String pattern) {
    int count = 0;
    boolean inEscape = false;
    for (int i = 0; i < pattern.length(); i++) {
      char ch = pattern.charAt(i);
      if (inEscape) {
        inEscape = false;
        continue;
      }
      if (ch == '\\') {
        inEscape = true;
      } else if (ch == '(' && i + 1 < pattern.length()) {
        // Check if it's a capturing group (not (?:...))
        if (i + 2 < pattern.length()
            && pattern.charAt(i + 1) == '?'
            && pattern.charAt(i + 2) == ':') {
          continue; // Non-capturing
        }
        count++;
      }
    }
    return count;
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
  private void generateConstructor(ClassWriter cw, boolean needsNFAState, NFA nfa) {
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

    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); // Computed automatically
    mv.visitEnd();
  }
}
