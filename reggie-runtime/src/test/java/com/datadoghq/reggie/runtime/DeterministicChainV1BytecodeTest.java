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
 * Stage 2 of DETERMINISTIC_CHAIN_BYTECODE (see
 * doc/2026-09-01-deterministic-chain-bytecode-design.md §9): executes the v1 generated code — a
 * single chain branch of LITERAL / CLASS1 / GREEDY_LOOP elements — and pins parity against {@code
 * java.util.regex} for the {@code matches}/{@code find}/{@code findFrom} entry points.
 *
 * <p>The strategy is not yet wired into routing (stage 5), so the test builds the generated class
 * directly: detect → generate → defineHiddenClass → instantiate, mirroring RuntimeCompiler's
 * class-shape (constructor + the three abstract methods; the rich MatchResult API inherits the
 * JDK-delegate defaults, which is fine because v1 patterns have no groups).
 */
class DeterministicChainV1BytecodeTest {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static int classCounter;

  /** Builds and instantiates the v1 generated matcher for a pattern the detector admits. */
  private static ReggieMatcher compileChain(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, new ThompsonBuilder().build(ast, 0));
    PatternAnalyzer.DeterministicChainInfo info = analyzer.detectDeterministicChain(ast);
    assertNotNull(info, "pattern must be detected as a deterministic chain: " + pattern);

    String className = "com.datadoghq.reggie.runtime.ReggieMatcher$DC" + (classCounter++);
    String internal = className.replace('.', '/');
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        Opcodes.V21,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        internal,
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        null);

    // Constructor: super(pattern) — same shape as RuntimeCompiler.generateConstructor for
    // strategies without NFA state.
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

    DeterministicChainBytecodeGenerator gen = new DeterministicChainBytecodeGenerator(info, 0);
    gen.generateMatchesMethod(cw, className);
    gen.generateFindMethod(cw, className);
    gen.generateFindFromMethod(cw, className);
    gen.generateFallbackSupport(cw, className);
    cw.visitEnd();

    Class<?> clazz =
        LOOKUP
            .defineHiddenClass(cw.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE)
            .lookupClass();
    return (ReggieMatcher) clazz.getDeclaredConstructor(String.class).newInstance(pattern);
  }

  /** Asserts matches/find/findFrom parity against java.util.regex over one (pattern, input). */
  private static void assertParity(ReggieMatcher m, java.util.regex.Pattern jdk, String input) {
    boolean expectedMatches = jdk.matcher(input).matches();
    assertEquals(expectedMatches, m.matches(input), "matches(\"" + input + "\")");

    boolean expectedFind = jdk.matcher(input).find();
    assertEquals(expectedFind, m.find(input), "find(\"" + input + "\")");

    // JDK Matcher.find(start) throws for start > length, while Reggie's findFrom convention
    // (all generated strategies) returns -1 — compare over the legal range only.
    for (int start = 0; start <= input.length(); start++) {
      Matcher jm = jdk.matcher(input);
      int expected = jm.find(start) ? jm.start() : -1;
      int actual = m.findFrom(input, start);
      final int s = start;
      assertEquals(expected, actual, () -> "findFrom(\"" + input + "\", " + s + ")");
    }
  }

  // The v1 element surface, one pattern per shape family.
  private static final String[] V1_PATTERNS = {
    "[a-z]+@", // loop + literal
    "\\w+@", // multi-range loop + literal
    "//[^@]+@", // literal + negated-class loop + literal
    "a*b", // min-0 loop + literal (first-set spans loop AND literal)
    "ab*c", // literal + min-0 loop + literal
    "[0-9]+-[0-9]+", // loop, literal, loop
    "x*yy", // min-0 loop + multi-char literal
  };

  @Test
  void v1PatternsMatchJdkAcrossShapes() throws Exception {
    for (String pattern : V1_PATTERNS) {
      ReggieMatcher m = compileChain(pattern);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);
      for (String input : inputsFor(pattern)) {
        assertParity(m, jdk, input);
      }
    }
  }

  @Test
  void findFromStartBoundsMirrorJdk() throws Exception {
    ReggieMatcher m = compileChain("[a-z]+@");
    assertEquals(-1, m.findFrom("", 0));
    assertEquals(-1, m.findFrom("abc@", 4));
    assertEquals(-1, m.findFrom("abc@", 5));
    // Negative start clamps to 0 (runtime convention: PikeVM/BitState clamp; JDK throws).
    assertEquals(0, m.findFrom("abc@", -1));
  }

  @Test
  void adversarialReconsumptionIsCorrectThoughUnbudgeted() throws Exception {
    // Stage-4 budget caveat (class javadoc): /[a-z]+@ against "zzzz…" re-consumes runs at every
    // gate hit — O(n²) but CORRECT. Keep the input modest until the budget lands.
    ReggieMatcher m = compileChain("[a-z]+@");
    String adversarial = "z".repeat(2000);
    assertFalse(m.matches(adversarial));
    assertFalse(m.find(adversarial));
    assertEquals(-1, m.findFrom(adversarial, 0));
    assertEquals(-1, m.findFrom(adversarial, 1000));
    String withTail = "z".repeat(500) + "@";
    assertTrue(m.find(withTail));
    assertEquals(0, m.findFrom(withTail, 0));
  }

  @Test
  void multiRangeLoopClassesMatch() throws Exception {
    // \w spans several ranges (a-z, A-Z, 0-9, _) — exercises the unrolled multi-range loop check.
    ReggieMatcher m = compileChain("\\w+@");
    assertTrue(m.matches("a_B9@"));
    assertFalse(m.matches("a B9@"));
    assertTrue(m.find("xx !a_B9@ yy"));
    assertEquals(4, m.findFrom("xx !a_B9@ yy", 0));
  }

  private static List<String> inputsFor(String pattern) {
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    inputs.add("@");
    inputs.add("a");
    inputs.add("a@");
    inputs.add("ab@");
    inputs.add("a@@");
    inputs.add("@a");
    inputs.add("xx" + patternChar(pattern, true) + "@" + patternChar(pattern, false));
    inputs.add("z".repeat(64));
    inputs.add("z".repeat(64) + "@");
    inputs.add("/".repeat(64));
    inputs.add("//" + "a".repeat(64) + "@");
    inputs.add("//" + "a".repeat(64));
    inputs.add("!" + patternChar(pattern, true) + "@#");
    inputs.add("aa@bb@cc");
    inputs.add("0-1-2-3-@");
    return inputs;
  }

  /** A representative in-class char for the pattern's first loop/class element. */
  private static char patternChar(String pattern, boolean first) {
    if (pattern.contains("[^@]")) {
      return first ? 'q' : 'r';
    }
    if (pattern.contains("[0-9]")) {
      return first ? '3' : '7';
    }
    if (pattern.startsWith("x") || pattern.startsWith("a")) {
      return first ? 'a' : 'b';
    }
    return first ? 'm' : 'n';
  }
}
