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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.codegen.DeterministicChainBytecodeGenerator;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * V2-alpha of DETERMINISTIC_CHAIN_BYTECODE (design doc §10): the greedy give-back loop (a greedy
 * single-char-body loop whose class intersects the remainder first-set retries the downstream tail
 * at each successively shorter boundary — JDK backtracking order, work-budget charged, PikeVM
 * fallback at overflow, including in matches/match), the terminal ALT_CHAIN (compound alternation
 * ending a sequence), and the WORDB element ({@code \b}, ASCII word set).
 */
class DeterministicChainV2AlphaBytecodeTest {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static int classCounter;

  private static final class Compiled {
    final ReggieMatcher m;
    final Class<?> clazz;

    Compiled(ReggieMatcher m, Class<?> clazz) {
      this.m = m;
      this.clazz = clazz;
    }

    long fallbackCount() throws Exception {
      Method f = clazz.getMethod("fallbackCount");
      return (long) f.invoke(m);
    }
  }

  private static Compiled compileChain(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, new ThompsonBuilder().build(ast, 0));
    PatternAnalyzer.DeterministicChainInfo info = analyzer.detectDeterministicChain(ast);
    assertNotNull(info, "pattern must be detected as a deterministic chain: " + pattern);

    String className = "com.datadoghq.reggie.runtime.ReggieMatcher$D2Ax" + (classCounter++);
    String internal = className.replace('.', '/');
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        internal,
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        null);

    MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ALOAD, 1);
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();

    int groupCount = 0;
    for (PatternAnalyzer.DeterministicChainInfo.ChainBranch b : info.branches) {
      groupCount = Math.max(groupCount, countCaptures(b.seq));
    }
    DeterministicChainBytecodeGenerator gen =
        new DeterministicChainBytecodeGenerator(info, groupCount);
    gen.generateMatchesMethod(cw, className);
    gen.generateMatchMethod(cw, className);
    gen.generateFindMethod(cw, className);
    gen.generateFindFromMethod(cw, className);
    gen.generateFindMatchMethod(cw, className);
    gen.generateFindMatchFromMethod(cw, className);
    gen.generateFindBoundsFromMethod(cw, className);
    gen.generateFallbackSupport(cw, className);
    cw.visitEnd();

    Class<?> clazz =
        LOOKUP
            .defineHiddenClass(cw.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE)
            .lookupClass();
    return new Compiled(
        (ReggieMatcher) clazz.getDeclaredConstructor(String.class).newInstance(pattern), clazz);
  }

  private static int countCaptures(PatternAnalyzer.DeterministicChainInfo.ChainSeq seq) {
    int max = 0;
    for (PatternAnalyzer.DeterministicChainInfo.ChainElem e : seq.elems) {
      if (e.kind == PatternAnalyzer.DeterministicChainInfo.ElemKind.CAPTURE) {
        max = Math.max(max, Math.max(e.groupNumber, countCaptures(e.nested)));
      } else if (e.nested != null) {
        max = Math.max(max, countCaptures(e.nested));
      } else if (e.alts != null) {
        for (PatternAnalyzer.DeterministicChainInfo.ChainSeq alt : e.alts) {
          max = Math.max(max, countCaptures(alt));
        }
      }
    }
    return max;
  }

  private static void assertSameSpans(MatchResult r, Matcher jm, String where) {
    assertNotNull(r, where + ": expected a match");
    assertEquals(jm.groupCount(), r.groupCount(), where + ": groupCount");
    for (int g = 0; g <= jm.groupCount(); g++) {
      assertEquals(jm.start(g), r.start(g), where + ": start(" + g + ")");
      assertEquals(jm.end(g), r.end(g), where + ": end(" + g + ")");
      assertEquals(jm.group(g), r.group(g), where + ": group(" + g + ")");
    }
  }

  private static void assertFullParity(Compiled c, java.util.regex.Pattern jdk, String input) {
    String where = "on \"" + (input.length() > 40 ? input.substring(0, 40) + "…" : input) + "\"";
    Matcher jm = jdk.matcher(input);
    if (jm.matches()) {
      assertTrue(c.m.matches(input), where + ": matches");
      assertSameSpans(c.m.match(input), jm, where + ": match()");
    } else {
      assertFalse(c.m.matches(input), where + ": matches");
      assertNull(c.m.match(input), where + ": match()");
    }
    for (int start = 0; start <= input.length(); start++) {
      String at = where + ", start=" + start;
      jm = jdk.matcher(input);
      if (jm.find(start)) {
        assertEquals(jm.start(), c.m.findFrom(input, start), at + ": findFrom");
        assertSameSpans(c.m.findMatchFrom(input, start), jm, at + ": findMatchFrom");
        int[] bounds = new int[2];
        assertTrue(c.m.findBoundsFrom(input, start, bounds), at + ": findBoundsFrom");
        assertEquals(jm.start(), bounds[0], at + ": bounds[0]");
        assertEquals(jm.end(), bounds[1], at + ": bounds[1]");
      } else {
        assertEquals(-1, c.m.findFrom(input, start), at + ": findFrom (no match)");
        assertNull(c.m.findMatchFrom(input, start), at + ": findMatchFrom (no match)");
        assertFalse(c.m.findBoundsFrom(input, start, new int[2]), at + ": findBoundsFrom (none)");
      }
    }
  }

  // =====================================================================================
  // E3: greedy give-back loops.
  // =====================================================================================

  @Test
  void xmlTagsGreedyGivebackParity() throws Exception {
    // The NFAFallbackBenchmark XmlTags shape: greedy .* needs give-back against the </\w+> tail.
    Compiled c = compileChain("(<\\w+>).*(</\\w+>)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(<\\w+>).*(</\\w+>)");
    assertFullParity(c, jdk, "<a>x</a>");
    assertFullParity(c, jdk, "<a></a>");
    assertFullParity(c, jdk, "y <t> mid </t> z");
    assertFullParity(c, jdk, "<a>");
    assertFullParity(c, jdk, "<a></b>");
    assertFullParity(c, jdk, "<a></a>x");
    assertFullParity(c, jdk, "<ab></ab>");
    assertFullParity(c, jdk, "");
    assertFullParity(c, jdk, "<h1>title</h1><p>body</p><div>nav</div><span>extra long tail</span>");
  }

  @Test
  void givebackMatchesRetriesThroughWholeInputCheck() throws Exception {
    // matches() must drive the give-back through the whole-input failure: the maximal run's tail
    // success is only accepted when it also ends at len.
    Compiled c = compileChain("a.*b");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("a.*b");
    for (String in : new String[] {"ab", "axb", "axxb", "axbxb", "axbxbxb", "abx", "x"}) {
      String at = "on \"" + in + "\"";
      assertEquals(jdk.matcher(in).matches(), c.m.matches(in), at + ": matches");
    }
  }

  @Test
  void givebackMinOneFloorParity() throws Exception {
    // The loop keeps its minimum across give-backs: [a-z]+a on "bab" has no split, on "xa" the
    // floor admits exactly one retry.
    Compiled c = compileChain("[a-z]+a");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[a-z]+a");
    assertFullParity(c, jdk, "bab");
    assertFullParity(c, jdk, "xa");
    assertFullParity(c, jdk, "xaab");
    assertFullParity(c, jdk, "ab");
    assertFullParity(c, jdk, "a");
    assertFullParity(c, jdk, "aa");
  }

  @Test
  void nestedGivebackParity() throws Exception {
    // Two give-back loops on one path: an inner failure backs out through the outer loop's
    // retry (innermost first, JDK order).
    Compiled c = compileChain("a.*b.*c");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("a.*b.*c");
    assertFullParity(c, jdk, "abc");
    assertFullParity(c, jdk, "axxbyyc");
    assertFullParity(c, jdk, "a1b2c3d");
    assertFullParity(c, jdk, "acbc");
    assertFullParity(c, jdk, "acb");
    assertFullParity(c, jdk, "aaabbbccc");
    assertFullParity(c, jdk, "bca");
    assertFullParity(c, jdk, "");
  }

  @Test
  void givebackInsideCaptureParity() throws Exception {
    // The capture's end moves with the give-back: the winning tail try's boundary is the group end
    // (the closing write re-executes per retry, last write wins).
    Compiled c = compileChain("(a.*)(b)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(a.*)(b)");
    assertFullParity(c, jdk, "axxb");
    assertFullParity(c, jdk, "abxb");
    assertFullParity(c, jdk, "axbxbb");
    assertFullParity(c, jdk, "xb");
    assertFullParity(c, jdk, "");
  }

  @Test
  void givebackDollarAnchorParity() throws Exception {
    // A $ tail rides the give-back retry: the maximal run must back up until $ holds.
    Compiled c = compileChain("x.*y$");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("x.*y$");
    assertFullParity(c, jdk, "xy");
    assertFullParity(c, jdk, "xay");
    assertFullParity(c, jdk, "xayy");
    assertFullParity(c, jdk, "xayz");
    assertFullParity(c, jdk, "xay\nz");
  }

  @Test
  void givebackWithLazyTailParity() throws Exception {
    // A lazy scan inside the give-back tail: the inner lazy loop exhausts into the outer
    // give-back retry.
    Compiled c = compileChain("a.*b[0-9]*?c");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("a.*b[0-9]*?c");
    assertFullParity(c, jdk, "abc");
    assertFullParity(c, jdk, "axxb123c45c");
    assertFullParity(c, jdk, "abbc");
    assertFullParity(c, jdk, "axbc9");
  }

  // =====================================================================================
  // E1: word boundaries.
  // =====================================================================================

  @Test
  void wordBoundaryParity() throws Exception {
    Compiled c = compileChain("\\b\\d+x");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("\\b\\d+x");
    assertFullParity(c, jdk, "42x");
    assertFullParity(c, jdk, "a42x");
    assertFullParity(c, jdk, "_42x");
    assertFullParity(c, jdk, "42");
    assertFullParity(c, jdk, "x");
  }

  @Test
  void wordBoundaryMidSeqParity() throws Exception {
    Compiled c = compileChain("a\\bb");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("a\\bb");
    assertFullParity(c, jdk, "a b");
    assertFullParity(c, jdk, "ab");
    assertFullParity(c, jdk, "a1b");
    assertFullParity(c, jdk, "xa1by");
    assertFullParity(c, jdk, "a b a b");
  }

  @Test
  void wordBoundaryAtSeqEndParity() throws Exception {
    Compiled c = compileChain("[0-9]+\\b");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[0-9]+\\b");
    assertFullParity(c, jdk, "42");
    assertFullParity(c, jdk, "42x");
    assertFullParity(c, jdk, "x42");
    assertFullParity(c, jdk, "4_2");
    assertFullParity(c, jdk, "x42y");
  }

  // =====================================================================================
  // E2: terminal ALT_CHAIN.
  // =====================================================================================

  @Test
  void altChainTerminalParity() throws Exception {
    // Compound alternatives (loops inside) that a LIT_ALT cannot express; sequential priority.
    Compiled c = compileChain("q(?:x[0-9]+[fd]|[0-9]+\\.[0-9]+)");
    java.util.regex.Pattern jdk =
        java.util.regex.Pattern.compile("q(?:x[0-9]+[fd]|[0-9]+\\.[0-9]+)");
    assertFullParity(c, jdk, "qx12f");
    assertFullParity(c, jdk, "qx12");
    assertFullParity(c, jdk, "q3.14");
    assertFullParity(c, jdk, "q31.4");
    assertFullParity(c, jdk, "qz");
    assertFullParity(c, jdk, "zqx1f2");
  }

  @Test
  void altChainWithWordBoundaryParity() throws Exception {
    // A leading \b inside an alternative (the SqlAnsi numeric-alternative shape, flat form).
    Compiled c = compileChain("q(?:\\b\\d+[fd]|[a-z]+)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("q(?:\\b\\d+[fd]|[a-z]+)");
    assertFullParity(c, jdk, "q42f");
    assertFullParity(c, jdk, "q42E13f");
    assertFullParity(c, jdk, "q42");
    assertFullParity(c, jdk, "qabc");
    assertFullParity(c, jdk, "q42x");
    assertFullParity(c, jdk, "xq42");
    assertFullParity(c, jdk, "q");
  }

  @Test
  void altChainRetryableBodyDeclines() throws Exception {
    // A give-back loop or an OPT inside an alternative body is v2-beta territory: the shared
    // rest's failures would need to re-enter the winning body's live retry, which the JVM
    // verifier rejects without per-body slot pre-initialization. The detector declines.
    RegexNode gb = new RegexParser().parse("q(?:x[0-9x]+x|[0-9]+\\.[0-9]+)");
    assertNull(
        new PatternAnalyzer(gb, new ThompsonBuilder().build(gb, 0)).detectDeterministicChain(gb),
        "give-back inside an alt body must decline");
    RegexNode ob = new RegexParser().parse("q(?:\\b\\d+(?:E[+-]?\\d+)?|[a-z]+)");
    assertNull(
        new PatternAnalyzer(ob, new ThompsonBuilder().build(ob, 0)).detectDeterministicChain(ob),
        "OPT inside an alt body must decline");
  }

  @Test
  void givebackOverflowDelegatesToPikeVMFallback() throws Exception {
    // Nested give-backs over an adversarial run are quadratic in the budget's eyes: the scan
    // must trip the budget and delegate to the linear PikeVM fallback (same answer, counted).
    Compiled c = compileChain("x[a-z]*y[a-z]*z");
    // A run of y's makes the give-backs nest: loop1 retries at each y boundary, and loop2
    // re-consumes the remaining y-run before failing z — quadratic work the budget must catch.
    StringBuilder sb = new StringBuilder("x");
    for (int i = 0; i < 1500; i++) {
      sb.append('a');
    }
    for (int i = 0; i < 1500; i++) {
      sb.append('y');
    }
    sb.append('q');
    String input = sb.toString();
    assertEquals(-1, c.m.findFrom(input, 0), "no match, via fallback");
    assertTrue(c.fallbackCount() > 0, "budget must have tripped");
    // The fallback matcher is a PikeVM instance with the same semantics.
    assertFalse(c.m.matches(input));
    assertTrue(c.fallbackCount() > 0, "matches overflow also delegates");
  }

  @Test
  void linearInputsStayOnTheGeneratedPath() throws Exception {
    // A well-behaved input never trips the budget: the generated path answers alone.
    Compiled c = compileChain("x[a-z]*y[a-z]*z");
    String input = "xa" + "b".repeat(50) + "y" + "c".repeat(50) + "z";
    assertEquals(0, c.m.findFrom(input, 0));
    assertEquals(0, c.fallbackCount(), "linear input must not delegate");
    assertTrue(c.m.matches(input));
    assertEquals(0, c.fallbackCount());
  }
}
