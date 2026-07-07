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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.codegen.ast.ConcatNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.codegen.BackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.codegen.PinnedBackreferenceBytecodeGenerator;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Evidence-gathering test for a FUTURE decision about retiring {@code detectHTMLTagPattern} /
 * {@code detectRepeatedWordPattern} in favor of the general {@code detectPinnedBackreference}
 * predicate (design doc {@code doc/2026-07-06-backreference-pinned-boundary-design.md}, §5/§6; impl
 * plan Task 8.4).
 *
 * <p>This test does NOT change routing or retire either hardcoded detector - it only (a) records,
 * for a corpus of tag-close-shaped and repeated-word-shaped pattern variants, whether the general
 * detector and the corresponding hardcoded detector agree on eligibility, and (b) where both are
 * eligible for the same pattern, confirms the two independently-generated matchers (({@code
 * PinnedBackreferenceBytecodeGenerator} vs. {@code BackreferenceBytecodeGenerator}) produce
 * byte-identical {@code matches}/{@code find}/{@code findFrom} results on a shared input corpus.
 *
 * <p>Disagreement between the two detectors for a given variant is an expected, documented outcome
 * for this test - not necessarily a bug (see per-case comments below for why each disagreement is
 * unsurprising given each detector's known limitations).
 */
class PinnedBackreferenceEquivalenceTest {

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  /**
   * Shared differential input corpus exercised against any repeated-word pattern eligible for both
   * detectors (no tag-close-shaped variant in this corpus is eligible for both - see the
   * eligibility tests below - so no tag-input corpus is needed).
   */
  private static final String[] WORD_INPUTS = {
    "hello hello",
    "the the cat",
    "hello world",
    "word   word",
    "foo\tfoo",
    "",
    "single",
    "hello hello hello",
    "Hello hello",
    "  hello hello  ",
  };

  private static final class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }

  private static RegexNode parse(String pattern) throws Exception {
    return new RegexParser().parse(pattern);
  }

  private static int countGroups(String pattern) {
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
        if (i + 2 < pattern.length()
            && pattern.charAt(i + 1) == '?'
            && pattern.charAt(i + 2) == ':') {
          continue;
        }
        count++;
      }
    }
    return count;
  }

  private static PatternAnalyzer newAnalyzer(String pattern, RegexNode ast) {
    ThompsonBuilder builder = new ThompsonBuilder();
    try {
      NFA nfa = builder.build(ast, countGroups(pattern));
      return new PatternAnalyzer(ast, nfa);
    } catch (UnsupportedOperationException e) {
      return new PatternAnalyzer(ast, null);
    }
  }

  private static PinnedBackreferenceInfo detectPinned(PatternAnalyzer analyzer, RegexNode ast)
      throws Exception {
    Method m =
        PatternAnalyzer.class.getDeclaredMethod("detectPinnedBackreference", RegexNode.class);
    m.setAccessible(true);
    return (PinnedBackreferenceInfo) m.invoke(analyzer, ast);
  }

  private static BackreferencePatternInfo detectHtmlTag(
      PatternAnalyzer analyzer, List<RegexNode> children) throws Exception {
    Method m = PatternAnalyzer.class.getDeclaredMethod("detectHTMLTagPattern", List.class);
    m.setAccessible(true);
    return (BackreferencePatternInfo) m.invoke(analyzer, children);
  }

  private static BackreferencePatternInfo detectRepeatedWord(
      PatternAnalyzer analyzer, List<RegexNode> children) throws Exception {
    Method m = PatternAnalyzer.class.getDeclaredMethod("detectRepeatedWordPattern", List.class);
    m.setAccessible(true);
    return (BackreferencePatternInfo) m.invoke(analyzer, children);
  }

  /** Eligibility snapshot for a single pattern variant. */
  private static final class Eligibility {
    final boolean pinned;
    final boolean hardcoded;

    Eligibility(boolean pinned, boolean hardcoded) {
      this.pinned = pinned;
      this.hardcoded = hardcoded;
    }
  }

  private static Eligibility eligibilityForTag(String pattern) throws Exception {
    RegexNode ast = parse(pattern);
    PatternAnalyzer analyzer = newAnalyzer(pattern, ast);
    PinnedBackreferenceInfo pinned = detectPinned(analyzer, ast);
    BackreferencePatternInfo html =
        ast instanceof ConcatNode ? detectHtmlTag(analyzer, ((ConcatNode) ast).children) : null;
    return new Eligibility(pinned != null, html != null);
  }

  private static Eligibility eligibilityForWord(String pattern) throws Exception {
    RegexNode ast = parse(pattern);
    PatternAnalyzer analyzer = newAnalyzer(pattern, ast);
    PinnedBackreferenceInfo pinned = detectPinned(analyzer, ast);
    BackreferencePatternInfo word =
        ast instanceof ConcatNode
            ? detectRepeatedWord(analyzer, ((ConcatNode) ast).children)
            : null;
    return new Eligibility(pinned != null, word != null);
  }

  // ── Eligibility agreement: HTML-tag-close-shaped corpus ─────────────────

  /**
   * {@code <(\w+)>.*</\1>} - the canonical tag-close shape. The general detector rejects this: the
   * separator between the group's close and the backref site ({@code >}, {@code .*}, {@code </})
   * spans three AST nodes, and {@code detectPinnedBackreference} only accepts a single-node
   * separator (see its own doc comment). The hardcoded detector accepts it. This is the documented,
   * expected disagreement this test exists to record - not a bug.
   */
  @TestFactory
  DynamicTest htmlCanonicalShapeDisagreement() {
    return DynamicTest.dynamicTest(
        "<(\\w+)>.*</\\1> : pinned rejects (multi-node separator), html accepts",
        () -> {
          Eligibility e = eligibilityForTag("<(\\w+)>.*</\\1>");
          assertEquals(false, e.pinned, "pinned detector expected to reject multi-node separator");
          assertEquals(true, e.hardcoded, "hardcoded HTML_TAG detector expected to accept");
        });
  }

  /** Same shape with a restricted tag-name charset - same disagreement, for the same reason. */
  @TestFactory
  DynamicTest htmlRestrictedCharsetDisagreement() {
    return DynamicTest.dynamicTest(
        "<([a-zA-Z]+)>.*</\\1> : same multi-node-separator disagreement",
        () -> {
          Eligibility e = eligibilityForTag("<([a-zA-Z]+)>.*</\\1>");
          assertEquals(false, e.pinned);
          assertEquals(true, e.hardcoded);
        });
  }

  /**
   * {@code \w*} instead of {@code \w+} - both detectors reject: the general detector's
   * unbounded-greedy-min-1 requirement excludes {@code min == 0}, and the hardcoded detector's
   * quantifier check explicitly requires {@code +}.
   */
  @TestFactory
  DynamicTest htmlStarQuantifierBothReject() {
    return DynamicTest.dynamicTest(
        "<(\\w*)>.*</\\1> : both reject (min=0 quantifier)",
        () -> {
          Eligibility e = eligibilityForTag("<(\\w*)>.*</\\1>");
          assertEquals(false, e.pinned);
          assertEquals(false, e.hardcoded);
        });
  }

  /** No middle content between the tags - both reject (hardcoded needs exactly 8 children). */
  @TestFactory
  DynamicTest htmlNoMiddleBothReject() {
    return DynamicTest.dynamicTest(
        "<(\\w+)></\\1> : both reject (no middle .*, wrong child count for HTML_TAG)",
        () -> {
          Eligibility e = eligibilityForTag("<(\\w+)></\\1>");
          assertEquals(false, e.pinned);
          assertEquals(false, e.hardcoded);
        });
  }

  // ── Eligibility agreement: repeated-word-shaped corpus ───────────────────

  /**
   * {@code \b(\w+)\s+\1\b} - the canonical repeated-word shape. Both detectors accept: the
   * separator here is a single node ({@code \s+}) whose charset (whitespace) is disjoint from the
   * group's charset (word chars), satisfying the general detector's single-node-separator
   * requirement, and matching the hardcoded shape exactly.
   */
  @TestFactory
  DynamicTest repeatedWordCanonicalShapeAgreement() {
    return DynamicTest.dynamicTest(
        "\\b(\\w+)\\s+\\1\\b : both accept",
        () -> {
          Eligibility e = eligibilityForWord("\\b(\\w+)\\s+\\1\\b");
          assertEquals(true, e.pinned);
          assertEquals(true, e.hardcoded);
        });
  }

  /** Restricted word-charset variant - same agreement. */
  @TestFactory
  DynamicTest repeatedWordRestrictedCharsetAgreement() {
    return DynamicTest.dynamicTest(
        "\\b([a-z]+)\\s+\\1\\b : both accept",
        () -> {
          Eligibility e = eligibilityForWord("\\b([a-z]+)\\s+\\1\\b");
          assertEquals(true, e.pinned);
          assertEquals(true, e.hardcoded);
        });
  }

  /**
   * {@code \s*} instead of {@code \s+} - both reject: the general detector's separator charset
   * (whitespace) is unaffected, but a {@code min == 0} separator can be zero-width, which the
   * hardcoded detector's quantifier check also rejects (requires {@code +}). Note: the general
   * detector's separator-disjointness check does not itself special-case zero-width separators;
   * both landed on "reject" here via each detector's own independent checks, not a shared rule.
   */
  @TestFactory
  DynamicTest repeatedWordStarSeparatorBothReject() {
    return DynamicTest.dynamicTest(
        "\\b(\\w+)\\s*\\1\\b : both reject (zero-width-capable separator)",
        () -> {
          Eligibility e = eligibilityForWord("\\b(\\w+)\\s*\\1\\b");
          assertEquals(false, e.pinned);
          assertEquals(false, e.hardcoded);
        });
  }

  /**
   * No word-boundary anchors, e.g. {@code (\w+)\s+\1} - the general detector still accepts (it has
   * no notion of word-boundary anchors at all; disjointness alone suffices), while the hardcoded
   * detector rejects (its structure requires exactly 5 children including both {@code \b}s). This
   * is a genuine eligibility divergence in the "pinned accepts, hardcoded rejects" direction,
   * distinct from the tag-close disagreement above.
   */
  @TestFactory
  DynamicTest repeatedWordNoBoundariesDivergence() {
    return DynamicTest.dynamicTest(
        "(\\w+)\\s+\\1 : pinned accepts (no boundary requirement), hardcoded rejects",
        () -> {
          Eligibility e = eligibilityForWord("(\\w+)\\s+\\1");
          assertEquals(true, e.pinned);
          assertEquals(false, e.hardcoded);
        });
  }

  /**
   * Literal separator instead of {@code \s+} - both reject (hardcoded needs a {@code \s+}
   * QuantifierNode; general detector's own disjointness proof would actually hold for a literal
   * separator too, but the shape doesn't match {@code detectRepeatedWordPattern} at all here since
   * we're only invoking it directly, not the pinned detector's separator-node acceptance path -
   * recorded for completeness).
   */
  @TestFactory
  DynamicTest repeatedWordLiteralSeparatorHardcodedRejects() {
    return DynamicTest.dynamicTest(
        "\\b(\\w+),\\1\\b : pinned accepts (literal separator disjoint from \\w), hardcoded rejects",
        () -> {
          Eligibility e = eligibilityForWord("\\b(\\w+),\\1\\b");
          assertEquals(true, e.pinned);
          assertEquals(false, e.hardcoded);
        });
  }

  // ── Byte-identical behavior where BOTH detectors agree the pattern is eligible ──

  private Object compilePinnedBooleanApi(PinnedBackreferenceInfo info) throws Exception {
    String className = "PinnedEquiv" + CLASS_COUNTER.incrementAndGet();
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        Opcodes.V21,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        className,
        null,
        "java/lang/Object",
        null);
    org.objectweb.asm.MethodVisitor ctor =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitCode();
    ctor.visitVarInsn(Opcodes.ALOAD, 0);
    ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    ctor.visitInsn(Opcodes.RETURN);
    ctor.visitMaxs(1, 1);
    ctor.visitEnd();

    PinnedBackreferenceBytecodeGenerator gen =
        new PinnedBackreferenceBytecodeGenerator(info, className);
    gen.generateMatchesMethod(cw);
    gen.generateFindMethod(cw);
    gen.generateFindFromMethod(cw);
    cw.visitEnd();

    byte[] bytecode = cw.toByteArray();
    Class<?> cls = new TestClassLoader().defineClass(className, bytecode);
    return cls.getDeclaredConstructor().newInstance();
  }

  private Object compileHardcodedBooleanApi(BackreferencePatternInfo info) throws Exception {
    String className = "HardcodedEquiv" + CLASS_COUNTER.incrementAndGet();
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        Opcodes.V21,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        className,
        null,
        "java/lang/Object",
        null);
    org.objectweb.asm.MethodVisitor ctor =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitCode();
    ctor.visitVarInsn(Opcodes.ALOAD, 0);
    ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    ctor.visitInsn(Opcodes.RETURN);
    ctor.visitMaxs(1, 1);
    ctor.visitEnd();

    BackreferenceBytecodeGenerator gen = new BackreferenceBytecodeGenerator(info);
    gen.generateMatchesMethod(cw, className);
    gen.generateFindMethod(cw, className);
    gen.generateFindFromMethod(cw, className);
    cw.visitEnd();

    byte[] bytecode = cw.toByteArray();
    Class<?> cls = new TestClassLoader().defineClass(className, bytecode);
    return cls.getDeclaredConstructor().newInstance();
  }

  private static boolean invokeMatches(Object matcher, String input) throws Exception {
    Method m = matcher.getClass().getMethod("matches", String.class);
    return (Boolean) m.invoke(matcher, input);
  }

  private static boolean invokeFind(Object matcher, String input) throws Exception {
    Method m = matcher.getClass().getMethod("find", String.class);
    return (Boolean) m.invoke(matcher, input);
  }

  private static int invokeFindFrom(Object matcher, String input, int from) throws Exception {
    Method m = matcher.getClass().getMethod("findFrom", String.class, int.class);
    return (Integer) m.invoke(matcher, input, from);
  }

  /**
   * Only the repeated-word canonical shape has both detectors eligible in this corpus (see
   * eligibility tests above), so it is the only case exercised for byte-identical behavior.
   */
  @TestFactory
  DynamicTest repeatedWordCanonicalShapeProducesIdenticalResults() throws Exception {
    String pattern = "\\b(\\w+)\\s+\\1\\b";
    RegexNode ast = parse(pattern);
    PatternAnalyzer analyzer = newAnalyzer(pattern, ast);
    PinnedBackreferenceInfo pinnedInfo = detectPinned(analyzer, ast);
    BackreferencePatternInfo hardcodedInfo =
        detectRepeatedWord(analyzer, ((ConcatNode) ast).children);
    assertNotNull(pinnedInfo, "expected pinned detector to accept the canonical shape");
    assertNotNull(hardcodedInfo, "expected hardcoded detector to accept the canonical shape");

    Object pinnedMatcher = compilePinnedBooleanApi(pinnedInfo);
    Object hardcodedMatcher = compileHardcodedBooleanApi(hardcodedInfo);

    return DynamicTest.dynamicTest(
        "matches/find/findFrom identical across WORD_INPUTS for " + pattern,
        () -> {
          for (String input : WORD_INPUTS) {
            assertEquals(
                invokeMatches(hardcodedMatcher, input),
                invokeMatches(pinnedMatcher, input),
                "matches() diverged for input: " + input);
            assertEquals(
                invokeFind(hardcodedMatcher, input),
                invokeFind(pinnedMatcher, input),
                "find() diverged for input: " + input);
            assertEquals(
                invokeFindFrom(hardcodedMatcher, input, 0),
                invokeFindFrom(pinnedMatcher, input, 0),
                "findFrom(input, 0) diverged for input: " + input);
          }
        });
  }
}
