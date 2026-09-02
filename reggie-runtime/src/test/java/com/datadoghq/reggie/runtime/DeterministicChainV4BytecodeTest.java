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
 * Stage 4 of DETERMINISTIC_CHAIN_BYTECODE (see
 * doc/2026-09-01-deterministic-chain-bytecode-design.md §4+§9): alternation of chains (priority
 * branch tries per scan position, ^-anchored branches only at position 0, union first-set gate),
 * the find() work budget {@code C×(len+1)}, and the budget-overflow fallback — the whole call
 * re-run by a lazily-parsed PikeVMMatcher, with the overflow observable via {@code
 * fallbackCount()}.
 */
class DeterministicChainV4BytecodeTest {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static int classCounter;

  /** The generated matcher plus its Class handle (for fallbackCount reflection). */
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

    String className = "com.datadoghq.reggie.runtime.ReggieMatcher$D4x" + (classCounter++);
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
  // Alternation of chains.
  // =====================================================================================

  @Test
  void twoUnanchoredBranchesParity() throws Exception {
    Compiled c = compileChain("foo[0-9]+|bar[0-9]+");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("foo[0-9]+|bar[0-9]+");
    for (String input :
        new String[] {
          "", "foo1", "bar2", "foobar1", "xfoo1y", "bar", "foo", "zbar9z", "foo12bar34"
        }) {
      assertFullParity(c, jdk, input);
    }
  }

  @Test
  void branchPriorityIsLeftmostFirstAlternative() throws Exception {
    // "ab|a" on "ab": both branches match at 0 — the FIRST alternative wins (JDK priority).
    Compiled c = compileChain("ab|a");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("ab|a");
    assertTrue(c.m.matches("ab"));
    assertEquals(0, c.m.findFrom("ab", 0));
    MatchResult r = c.m.findMatch("ab");
    assertEquals("ab", r.group());
    assertTrue(c.m.matches("a"));
    assertEquals("a", c.m.findMatch("a").group());
    for (String input : new String[] {"", "ab", "a", "xa", "xab", "aa", "ba"}) {
      assertFullParity(c, jdk, input);
    }
  }

  @Test
  void mixedAnchoredAndUnanchoredBranchesParity() throws Exception {
    // ^-anchored branch only tried at absolute position 0 — INCLUDING when its first char is
    // outside the unanchored branches' union gate (the gate skips position 0).
    Compiled c = compileChain("^ab|c+d");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("^ab|c+d");
    for (String input :
        new String[] {"", "ab", "zabcd", "abcd", "ccd", "xccd", "zab", "abz", "d", "c"}) {
      assertFullParity(c, jdk, input);
    }
    // ^ab does not match at position 0 ("z…"), and c+d matches at 3.
    assertEquals(3, c.m.findFrom("zabcd", 0));
  }

  @Test
  void allAnchoredBranchesTryOnlyPositionZero() throws Exception {
    Compiled c = compileChain("^ab|^cd");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("^ab|^cd");
    for (String input : new String[] {"", "ab", "cd", "zab", "abcd", "xcd", "abab", "cdab"}) {
      assertFullParity(c, jdk, input);
    }
    assertEquals(-1, c.m.findFrom("zab", 1));
  }

  @Test
  void endAnchoredBranchesParity() throws Exception {
    Compiled c = compileChain("[0-9]+$|x");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[0-9]+$|x");
    for (String input : new String[] {"", "1", "a1", "ab12", "a12b", "x", "ax", "12", "x12"}) {
      assertFullParity(c, jdk, input);
    }
  }

  @Test
  void urlAuthorityShapedAlternationParity() throws Exception {
    // The motivating UrlAuth shape (single-branch subset with capture): first branch is
    // ^-anchored, second is the query-params shape.
    Compiled c = compileChain("^(?:[^:]+:)?//([^/@]+)@|[?#&]([^=&;]+)=([^?#&]+)");
    java.util.regex.Pattern jdk =
        java.util.regex.Pattern.compile("^(?:[^:]+:)?//([^/@]+)@|[?#&]([^=&;]+)=([^?#&]+)");
    for (String input :
        new String[] {
          "",
          "//user@host",
          "http://user@host",
          "x://user@host",
          "?a=b",
          "#a=b",
          "&a=b",
          "http://u@h?a=b",
          "//u@h?a=b",
          "?a=b#c=d",
          "//@@",
          "z?a=b",
          "http://u@h",
          "?=",
          "?a=",
          "?a=b&c=d"
        }) {
      assertFullParity(c, jdk, input);
    }
  }

  // =====================================================================================
  // Work budget + PikeVM fallback.
  // =====================================================================================

  @Test
  void quadraticReconsumptionDelegatesToPikeVMFallback() throws Exception {
    // "//[^@]+@" vs "////…": every position passes the union gate, each failed try re-consumes
    // the whole '/'-run — O(n²) — so the budget (C×(len+1)) trips early and the whole call is
    // re-run by the PikeVM fallback. Correct answer, linear time, overflow observable.
    Compiled c = compileChain("//[^@]+@");
    String adversarial = "/".repeat(20000);
    long before = c.fallbackCount();
    assertEquals(-1, c.m.findFrom(adversarial, 0));
    long after = c.fallbackCount();
    assertTrue(
        after > before, "budget overflow must delegate (count " + before + " -> " + after + ")");

    // Delegated result agrees with JDK.
    assertFalse(java.util.regex.Pattern.compile("//[^@]+@").matcher(adversarial).find());

    // The fallback instance is reused across overflows (lazy, once per matcher).
    assertEquals(-1, c.m.findFrom(adversarial, 0));
    assertEquals(after + 1, c.fallbackCount());
  }

  @Test
  void linearInputsStayOnTheGeneratedPath() throws Exception {
    // Benign inputs must complete entirely in generated code: no overflow, no fallback.
    Compiled c = compileChain("foo[0-9]+|bar[0-9]+");
    String benign = "noise " + "foo123".repeat(500) + " tail " + "bar456".repeat(500);
    assertTrue(c.m.find(benign));
    assertEquals(6, c.m.findFrom(benign, 6));
    assertEquals(0, c.fallbackCount(), "linear input must not trip the budget");
  }

  @Test
  void overflowFallbackReturnsCapturedSpans() throws Exception {
    // A match that EXISTS but whose search path trips the budget: the PikeVM fallback must
    // return the full span result, not just a boolean.
    Compiled c = compileChain("([a-z]+)@");
    // Long z-runs that each fail at the '1' separator re-consume ~50 chars per gate position —
    // the budget (C×(len+1) with C≈7) trips long before the scan reaches the final real match.
    String input = ("z".repeat(50) + "1").repeat(200) + "z".repeat(50) + "@";
    MatchResult r = c.m.findMatch(input);
    assertNotNull(r);
    int matchStart = 200 * 51;
    assertEquals(matchStart, r.start());
    assertEquals(matchStart + 51, r.end());
    assertEquals("z".repeat(50), r.group(1));
    assertTrue(c.fallbackCount() > 0, "quadratic search path must trip the budget");
  }
}
