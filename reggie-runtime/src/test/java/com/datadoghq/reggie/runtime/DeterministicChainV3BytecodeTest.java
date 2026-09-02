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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Stage 3 of DETERMINISTIC_CHAIN_BYTECODE (see
 * doc/2026-09-01-deterministic-chain-bytecode-design.md §9): executes the v3 generated code —
 * single chain with CAPTURE / LIT_ALT / OPT / LAZY_LOOP elements and {@code ^}/{@code $} anchors —
 * and pins parity against {@code java.util.regex} for the boolean paths AND the group spans of the
 * result paths (match / findMatchFrom / findBoundsFrom).
 */
class DeterministicChainV3BytecodeTest {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static int classCounter;

  /** Builds and instantiates the v3 generated matcher (full surface) for an admitted pattern. */
  private static ReggieMatcher compileChain(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, new ThompsonBuilder().build(ast, 0));
    PatternAnalyzer.DeterministicChainInfo info = analyzer.detectDeterministicChain(ast);
    assertNotNull(info, "pattern must be detected as a deterministic chain: " + pattern);

    String className = "com.datadoghq.reggie.runtime.ReggieMatcher$D3" + (classCounter++);
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

    // groupCount comes from the capture elements the detector found (v3 patterns: 1..2).
    int groupCount = countCaptures(info);
    DeterministicChainBytecodeGenerator gen =
        new DeterministicChainBytecodeGenerator(info, groupCount);
    gen.generateMatchesMethod(cw, className);
    gen.generateMatchMethod(cw, className);
    gen.generateFindMethod(cw, className);
    gen.generateFindFromMethod(cw, className);
    gen.generateFindMatchMethod(cw, className);
    gen.generateFindMatchFromMethod(cw, className);
    gen.generateFindBoundsFromMethod(cw, className);
    cw.visitEnd();

    Class<?> clazz =
        LOOKUP
            .defineHiddenClass(cw.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE)
            .lookupClass();
    return (ReggieMatcher) clazz.getDeclaredConstructor(String.class).newInstance(pattern);
  }

  private static int countCaptures(PatternAnalyzer.DeterministicChainInfo info) {
    // Group numbers are contiguous 1..n in this family; take the max over the element tree.
    return countCaptures(info.branches.get(0).seq);
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

  /** Compares a generated MatchResult against a JDK matcher's current-match spans. */
  private static void assertSameSpans(MatchResult r, String input, Matcher jm, String where) {
    assertNotNull(r, where + ": expected a match, got null");
    assertEquals(jm.groupCount(), r.groupCount(), where + ": groupCount");
    for (int g = 0; g <= jm.groupCount(); g++) {
      assertEquals(jm.start(g), r.start(g), where + ": start(" + g + ")");
      assertEquals(jm.end(g), r.end(g), where + ": end(" + g + ")");
      assertEquals(jm.group(g), r.group(g), where + ": group(" + g + ")");
    }
  }

  /**
   * Full parity sweep for one (pattern, input): matches/match, find/findFrom, findBoundsFrom, and
   * findMatchFrom over every legal start — booleans AND spans.
   */
  private static void assertFullParity(ReggieMatcher m, java.util.regex.Pattern jdk, String input) {
    String where =
        "pattern on \"" + (input.length() > 40 ? input.substring(0, 40) + "…" : input) + "\"";

    Matcher jm = jdk.matcher(input);
    if (jm.matches()) {
      assertTrue(m.matches(input), where + ": matches");
      assertSameSpans(m.match(input), input, jm, where + ": match()");
    } else {
      assertFalse(m.matches(input), where + ": matches");
      assertNull(m.match(input), where + ": match() should be null");
    }

    for (int start = 0; start <= input.length(); start++) {
      String at = where + ", start=" + start;
      jm = jdk.matcher(input);
      if (jm.find(start)) {
        assertTrue(m.find(input) || start > 0, at + ": find consistency");
        int expectedStart = jm.start();
        assertEquals(expectedStart, m.findFrom(input, start), at + ": findFrom");

        int[] bounds = new int[2];
        assertTrue(m.findBoundsFrom(input, start, bounds), at + ": findBoundsFrom");
        assertEquals(expectedStart, bounds[0], at + ": bounds[0]");
        assertEquals(jm.end(), bounds[1], at + ": bounds[1]");

        // jm is already positioned on the find(start) match above.
        assertSameSpans(m.findMatchFrom(input, start), input, jm, at + ": findMatchFrom");
        if (start == 0) {
          assertSameSpans(m.findMatch(input), input, jm, at + ": findMatch");
        }
      } else {
        assertEquals(-1, m.findFrom(input, start), at + ": findFrom (no match)");
        assertFalse(m.findBoundsFrom(input, start, new int[2]), at + ": findBoundsFrom (no match)");
        assertNull(m.findMatchFrom(input, start), at + ": findMatchFrom (no match)");
      }
    }
  }

  // =====================================================================================
  // Element coverage.
  // =====================================================================================

  @Test
  void captureWithGreedyLoopParity() throws Exception {
    ReggieMatcher m = compileChain("(\\w+)@");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(\\w+)@");
    for (String input :
        new String[] {
          "", "a@", "ab@", "@", "user@host", "xx user@host yy", "a b@c", "user@@host"
        }) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void xmlTagsLazyScanWithCapturesParity() throws Exception {
    // The motivating NFAFallback shape: capture, lazy scan, capture.
    ReggieMatcher m = compileChain("(<\\w+>).*?(</\\w+>)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(<\\w+>).*?(</\\w+>)");
    for (String input :
        new String[] {
          "",
          "<a>",
          "</a>",
          "<a></a>",
          "<ab></ab>",
          "<a><b></a></b>",
          "xx <tag1> middle </tag2> yy",
          "<a1></a2>",
          "<a>no close",
          "no tags at all"
        }) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void litAltTriesInPriorityOrderParity() throws Exception {
    ReggieMatcher m = compileChain("(a|ab|abc)(1|12|123)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(a|ab|abc)(1|12|123)");
    for (String input :
        new String[] {
          "", "a1", "a12", "a123", "ab1", "ab12", "abc123", "abc12", "xa1y", "abc1", "a"
        }) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void captureAroundOptionalIsZeroWidthWhenSkipped() throws Exception {
    // Quantified capturing groups decline (outside the family), so the optional content is
    // non-capturing and the capture wraps the OPT: on the skip path group 1 is zero-width.
    ReggieMatcher m = compileChain("((?:ab)?)c");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("((?:ab)?)c");

    assertTrue(m.matches("c"));
    MatchResult r = m.match("c");
    assertEquals(0, r.start(1), "skipped optional content is a zero-width group");
    assertEquals(0, r.end(1));

    assertTrue(m.matches("abc"));
    r = m.match("abc");
    assertEquals(0, r.start(1));
    assertEquals(2, r.end(1));
    assertEquals("ab", r.group(1));

    for (String input : new String[] {"", "c", "abc", "zc", "zabc", "ab", "abbc"}) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void optionalOverClassParity() throws Exception {
    ReggieMatcher m = compileChain("[-+]?[0-9]+");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[-+]?[0-9]+");
    for (String input : new String[] {"", "1", "-1", "+1", "- 1", "a-42b", "--1", "12", "-"}) {
      assertFullParity(m, jdk, input);
    }
  }

  // =====================================================================================
  // Anchors.
  // =====================================================================================

  @Test
  void startAnchorTriesOnlyPositionZero() throws Exception {
    ReggieMatcher m = compileChain("^abc");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("^abc");
    for (String input : new String[] {"", "abc", "xabc", "abcx", "abcabc"}) {
      assertFullParity(m, jdk, input);
    }
    assertEquals(-1, m.findFrom("abc", 1));
    assertEquals(0, m.findFrom("abc", 0));
  }

  @Test
  void endAnchorRequiresRegionEnd() throws Exception {
    ReggieMatcher m = compileChain("[0-9]+$");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[0-9]+$");
    for (String input : new String[] {"", "1", "a1", "12a", "a1b2", "12", "x"}) {
      assertFullParity(m, jdk, input);
    }
    // A digit mid-input must NOT be returned as a match.
    assertEquals(-1, m.findFrom("1a", 0));
  }

  @Test
  void multilineEndAnchorMatchesBeforeLineTerminators() throws Exception {
    ReggieMatcher m = compileChain("(?m)\\w+$");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(?m)\\w+$");
    // NEL (\\u0085), LS (\\u2028) and PS (\\u2029) are $-terminators too — pin them.
    for (String input :
        new String[] {
          "",
          "ab",
          "ab\ncd",
          "ab\r\ncd",
          "ab\u0085cd",
          "ab\u2028cd",
          "ab\u2029cd",
          "ab\ncd\nef",
          "1a\n",
          "\nab"
        }) {
      assertFullParity(m, jdk, input);
    }
  }

  // =====================================================================================
  // Capture ends at lazy loops (the intricate bookkeeping — see generator javadoc).
  // =====================================================================================

  @Test
  void captureEndsAtLazyLoopParity() throws Exception {
    // (\w*?)@: group 1 ends at the START of the winning tail try, not after the '@'.
    ReggieMatcher m = compileChain("(\\w*?)@");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(\\w*?)@");
    for (String input : new String[] {"", "a@", "ab@", "@", "user@host", "ab@@c"}) {
      assertFullParity(m, jdk, input);
    }
    MatchResult r = m.findMatch("ab@cd");
    assertEquals(0, r.start(1));
    assertEquals(2, r.end(1));
    assertEquals("ab", r.group(1));
    assertEquals(3, r.end()); // match ends after the '@'
  }

  @Test
  void nestedCaptureBothEndAtLazyLoopParity() throws Exception {
    // ((\w*?))@: both groups close at the same lazy-loop end.
    ReggieMatcher m = compileChain("((\\w*?))@");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("((\\w*?))@");
    for (String input : new String[] {"", "a@", "ab@", "@", "ab@cd"}) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void captureEndWithTrailingOptionalLazyLoopParity() throws Exception {
    // ([a-z]+(?:[0-9]*?))@: on the OPT-matched path group 1 ends at the lazy loop's stop;
    // on the skip path it ends after the [a-z]+ run.
    ReggieMatcher m = compileChain("([a-z]+(?:[0-9]*?))@");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("([a-z]+(?:[0-9]*?))@");
    for (String input : new String[] {"", "ab@", "ab12@", "a1@", "ab1@", "@", "xy12@@z"}) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void lazyLoopInsideCaptureWithTrailingElementParity() throws Exception {
    // (\w*?x)@: the loop's tail stays INSIDE the capture — group 1 spans run + 'x'.
    ReggieMatcher m = compileChain("(\\w*?x)@");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(\\w*?x)@");
    for (String input : new String[] {"", "x@", "ax@", "abx@", "x@y", "@", "abx@cd"}) {
      assertFullParity(m, jdk, input);
    }
  }

  // =====================================================================================
  // Combined / adversarial.
  // =====================================================================================

  @Test
  void urlQueryStyleChainParity() throws Exception {
    // A UrlQuery-shaped single branch: gate chars {?#&}, capture, loop, literal, capture, loop.
    ReggieMatcher m = compileChain("[?#&]([^=&;]+)=([^?#&]+)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[?#&]([^=&;]+)=([^?#&]+)");
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    inputs.add("?");
    inputs.add("?a=b");
    inputs.add("?key=value&other=thing");
    inputs.add("x#y=z");
    inputs.add("&a=");
    inputs.add("?a=b=c");
    inputs.add("??a=b");
    inputs.add("?=b");
    inputs.add("zzz?a" + "b".repeat(50) + "=c");
    for (String input : inputs) {
      assertFullParity(m, jdk, input);
    }
  }

  @Test
  void adversarialInputsAreCorrectThoughUnbudgeted() throws Exception {
    // Stage-4 budget caveat: gate-passing failing tries re-consume runs. Correct, not yet linear.
    ReggieMatcher m = compileChain("(<\\w+>).*?(</\\w+>)");
    String adversarial = ("<a" + "x".repeat(2000) + ">").repeat(3);
    assertFalse(m.find(adversarial));
    assertEquals(-1, m.findFrom(adversarial, 0));
    assertNull(m.findMatch(adversarial));

    ReggieMatcher m2 = compileChain("(\\w+)@");
    String z = "z".repeat(3000);
    assertFalse(m2.find(z));
    assertTrue(m2.find(z + "@"));
  }
}
