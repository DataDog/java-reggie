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
 * V2-beta of DETERMINISTIC_CHAIN_BYTECODE (design doc §10): the LOOP_ALT element (a greedy loop
 * over a bounded disjunction of single-consume bodies — the SQL string-literal {@code (?:''|[^'])*}
 * shape), journaled iteration-boundary give-back through the per-thread scratch buffer
 * (ReggieMatcher.chainScratch — chain matchers are shared across threads per the concurrency
 * contract, so the journal can never be an instance field), bounded/higher-min class loops ({@code
 * -{5}}, {@code [a-z0-9]{13}}, {@code {100,}}), and the mid-seq ALT_CHAIN with retryable bodies
 * (slot pre-initialization lifts the v2-alpha flat/terminal restrictions).
 */
class DeterministicChainV2BetaBytecodeTest {

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

    String className = "com.datadoghq.reggie.runtime.ReggieMatcher$D2Bx" + (classCounter++);
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
  // E4: LOOP_ALT + journaled give-back.
  // =====================================================================================

  @Test
  void sqlStringLiteralParity() throws Exception {
    // The SQL string-literal shape: '' is the in-body escape, [^'] the content; the closing quote
    // must be reached by journal give-back when the loop consumed it as an escape pair.
    Compiled c = compileChain("'(?:''|[^'])*'");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("'(?:''|[^'])*'");
    assertFullParity(c, jdk, "''");
    assertFullParity(c, jdk, "'a'");
    assertFullParity(c, jdk, "'a''b'");
    assertFullParity(c, jdk, "'a''");
    assertFullParity(c, jdk, "''''");
    assertFullParity(c, jdk, "x'a''");
    assertFullParity(c, jdk, "'a'x");
    assertFullParity(c, jdk, "'a''b''c'");
    assertFullParity(c, jdk, "''a");
    assertFullParity(c, jdk, "a''b");
    assertFullParity(c, jdk, "'");
  }

  @Test
  void loopAltCapturesParity() throws Exception {
    // The capture's end moves with the journal give-back: the winning boundary is the group end.
    Compiled c = compileChain("('(?:''|[^'])*')z");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("('(?:''|[^'])*')z");
    assertFullParity(c, jdk, "'a''b'z");
    assertFullParity(c, jdk, "''z");
    assertFullParity(c, jdk, "'a''z");
    assertFullParity(c, jdk, "'a'z");
  }

  @Test
  void loopAltWithTailAndLazyParity() throws Exception {
    // A LOOP_ALT followed by more tail (mid-seq) and a lazy scan below: all retries chain.
    Compiled c = compileChain("'(?:''|[^'])*'[0-9]*?z");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("'(?:''|[^'])*'[0-9]*?z");
    assertFullParity(c, jdk, "'a''b'12z");
    assertFullParity(c, jdk, "'1'z");
    assertFullParity(c, jdk, "'a''z");
  }

  @Test
  void loopAltDisjointParity() throws Exception {
    // Disjoint LOOP_ALT (no give-back, no journal): plain maximal consumption.
    Compiled c = compileChain("x(?:%2[^2]|%[^2]|[^\"%])+z");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("x(?:%2[^2]|%[^2]|[^\"%])+z");
    assertFullParity(c, jdk, "x%2Az");
    assertFullParity(c, jdk, "x%Fz");
    assertFullParity(c, jdk, "xq\"z");
    assertFullParity(c, jdk, "xz");
    assertFullParity(c, jdk, "x%z");
  }

  @Test
  void loopAltMinPlusParity() throws Exception {
    // A min-1 LOOP_ALT (+: at least one iteration) with the journal floor at one boundary.
    Compiled c = compileChain("q(?:ab|a)+z");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("q(?:ab|a)+z");
    assertFullParity(c, jdk, "qabz");
    assertFullParity(c, jdk, "qababz");
    assertFullParity(c, jdk, "qaz");
    assertFullParity(c, jdk, "qba");
  }

  @Test
  void loopAltJournalOverflowDelegatesToPikeVM() throws Exception {
    // Adversarial quote-nesting over a long unmatched literal is quadratic work — the journal
    // give-back retries must trip the budget and delegate to PikeVM (same answer, counted).
    Compiled c = compileChain("'(?:''|[^'])*'q");
    StringBuilder sb = new StringBuilder("'");
    for (int i = 0; i < 2000; i++) {
      sb.append("''");
    }
    sb.append("'");
    String input = sb.toString(); // one huge closed literal; the q tail fails everywhere
    assertEquals(0, c.fallbackCount(), "nothing delegated yet");
    assertEquals(-1, c.m.findFrom(input, 0), "no match, via the linear fallback");
    assertTrue(c.fallbackCount() > 0, "budget must have tripped");
    // A linear input stays on the generated path: the count must not grow past the adversarial
    // run's (the fallback counter is cumulative per matcher instance).
    long before = c.fallbackCount();
    assertEquals(0, c.m.findFrom("'a'q", 0));
    assertEquals(before, c.fallbackCount(), "linear input must not delegate");
  }

  // =====================================================================================
  // Bounded / higher-min class loops.
  // =====================================================================================

  @Test
  void boundedExactLoopParity() throws Exception {
    Compiled c = compileChain("-{5}BEGINx");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("-{5}BEGINx");
    assertFullParity(c, jdk, "-----BEGINx");
    assertFullParity(c, jdk, "----BEGINx");
    assertFullParity(c, jdk, "y-----BEGINx");
    assertFullParity(c, jdk, "------BEGINx");
  }

  @Test
  void boundedRangeLoopParity() throws Exception {
    Compiled c = compileChain("[a-z0-9]{13}q");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("[a-z0-9]{13}q");
    assertFullParity(c, jdk, "abcdefghijklm" + "n".repeat(3) + "q");
    assertFullParity(c, jdk, "0123456789abcq");
    assertFullParity(c, jdk, "0123456789abq");
    assertFullParity(c, jdk, "0123456789abcdq");
  }

  @Test
  void boundedGivebackLoopParity() throws Exception {
    // A bounded-range loop over a class that intersects the tail: consume caps at max, the
    // give-back floor keeps min.
    Compiled c = compileChain("x[0-9x]{2,4}xz");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("x[0-9x]{2,4}xz");
    assertFullParity(c, jdk, "x12xz");
    assertFullParity(c, jdk, "xx1xx");
    assertFullParity(c, jdk, "x1234xz");
    assertFullParity(c, jdk, "x12345xz");
    assertFullParity(c, jdk, "x1xz");
  }

  @Test
  void higherMinLoopParity() throws Exception {
    Compiled c = compileChain("q[0-9]{100,}z");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("q[0-9]{100,}z");
    assertFullParity(c, jdk, "q" + "7".repeat(150) + "z");
    assertFullParity(c, jdk, "q" + "7".repeat(99) + "z");
  }

  // =====================================================================================
  // The real targets: SQL dialects + QueryObfuscator.
  // =====================================================================================

  @Test
  void sqlAnsiFullParity() throws Exception {
    String p =
        "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
            + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
            + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";
    Compiled c = compileChain(p);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p);
    assertFullParity(c, jdk, "SELECT * FROM t WHERE a = 'it''s' AND b = 0x1F");
    assertFullParity(c, jdk, "INSERT INTO t VALUES ('a''b''c')");
    assertFullParity(c, jdk, "-- comment till EOL");
    assertFullParity(c, jdk, "/* block\n comment */");
    assertFullParity(c, jdk, "42");
    assertFullParity(c, jdk, "3.14E-2f");
    assertFullParity(c, jdk, "x'AB12'");
    assertFullParity(c, jdk, "b'0101'");
    assertFullParity(c, jdk, "+0b1101");
    assertFullParity(c, jdk, "'unterminated");
    assertFullParity(c, jdk, "a = 'x' -- 'y'");
    assertFullParity(c, jdk, "SELECT 'a''b' FROM t /* c */ -- d");
  }

  @Test
  void sqlMysqlEscapedLiteralParity() throws Exception {
    String p =
        "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?"
            + "|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
            + "|\"(?:\\\\\"|[^\"])*\"|'(?:\\\\'|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";
    Compiled c = compileChain(p);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p);
    assertFullParity(c, jdk, "SET a = 'It\\'s fine' WHERE b = \"quoted \\\" str\"");
    assertFullParity(c, jdk, "SELECT \"abc\" FROM t");
    assertFullParity(c, jdk, "SELECT 'a\\'b' FROM t");
    assertFullParity(c, jdk, "x = \"open");
    assertFullParity(c, jdk, "1.5E3");
  }

  @Test
  void queryObfuscatorFullParity() throws Exception {
    String p =
        "(?i)(?:(?:\"|%22)?)(?:(?:old[-_]?|new[-_]?)?p(?:ass)?w(?:or)?d(?:1|2)?"
            + "|pass(?:[-_]?phrase)?|secret"
            + "|(?:api[-_]?|private[-_]?|public[-_]?|access[-_]?|secret[-_]?|app(?:lication)?[-_]?)"
            + "key(?:[-_]?id)?"
            + "|token|consumer[-_]?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)"
            + "(?:(?:\\s|%20)*(?:=|%3D)[^&]+"
            + "|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))"
            + "|(?:bearer(?:\\s|%20)+[a-z0-9._\\-]+"
            + "|token(?::|%3A)[a-z0-9]{13}"
            + "|gh[opsu]_[0-9a-zA-Z]{36}"
            + "|-{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY-{5}[^\\-]+-{5}END(?:[a-z\\s]|%20)"
            + "+PRIVATE(?:\\s|%20)KEY(?:-{5})?(?:\\n|%0A)?)";
    Compiled c = compileChain(p);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p);
    assertFullParity(c, jdk, "password=hunter2&user=bob");
    assertFullParity(c, jdk, "x?old_password%3Dsecret%20stuff&y=1");
    assertFullParity(c, jdk, "api-key: \"abc%2Fdef\"");
    assertFullParity(c, jdk, "consumer_secret=xyz");
    assertFullParity(c, jdk, "authorization=Bearer");
    assertFullParity(c, jdk, "bearer abc.def-ghi");
    assertFullParity(c, jdk, "token:0123456789abcd");
    assertFullParity(c, jdk, "ghp_" + "Ab1".repeat(12));
    assertFullParity(c, jdk, "-----BEGIN PRIVATE KEY-----x-----END PRIVATE KEY-----");
    assertFullParity(c, jdk, "q=\"pass : %2Fx\"&r=1");
    assertFullParity(c, jdk, "nothing=here");
    assertFullParity(c, jdk, "");
  }
}
