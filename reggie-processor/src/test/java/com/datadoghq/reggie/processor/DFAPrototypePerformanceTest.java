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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.codegen.automaton.AssertionCheck;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.codegen.DFAUnrolledBytecodeGenerator;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

/** Performance measurement for DFA prototype with assertions. Pattern: a(?=bc)bc */
public class DFAPrototypePerformanceTest {

  private static final int WARMUP_ITERATIONS = 100_000;
  private static final int BENCHMARK_ITERATIONS = 5_000_000;

  @Test
  public void measurePrototypePerformance() throws Exception {
    // Generate DFA matcher (our prototype)
    DFA dfa = buildDFAFor_a_lookaheadBC();
    Class<?> matcherClass = generateMatcher(dfa);
    Object matcher = matcherClass.getDeclaredConstructor().newInstance();
    Method matches = matcherClass.getMethod("matches", String.class);

    // Generate JDK Pattern for comparison
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile("a(?=bc)bc");

    // Test inputs
    String[] testInputs = {
      "abc", // Match
      "adc", // No match - lookahead fails
      "a", // No match - incomplete
      "ab", // No match - incomplete
      "xyz", // No match - first char fails
    };

    System.out.println("DFA Prototype Performance Test");
    System.out.println("Pattern: a(?=bc)bc");
    System.out.println("=".repeat(80));
    System.out.println();

    // Warmup
    System.out.println("Warming up (" + WARMUP_ITERATIONS + " iterations)...");
    for (String input : testInputs) {
      for (int i = 0; i < WARMUP_ITERATIONS / testInputs.length; i++) {
        matches.invoke(matcher, input);
        jdkPattern.matcher(input).matches();
      }
    }

    System.out.println("Running benchmarks (" + BENCHMARK_ITERATIONS + " iterations per input)...");
    System.out.println();

    long dfaTotalTime = 0;
    long jdkTotalTime = 0;

    // Benchmark each input
    for (String input : testInputs) {
      // Benchmark DFA prototype
      long dfaStart = System.nanoTime();
      boolean dfaResult = false;
      for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
        dfaResult = (Boolean) matches.invoke(matcher, input);
      }
      long dfaTime = System.nanoTime() - dfaStart;

      // Benchmark JDK Pattern
      long jdkStart = System.nanoTime();
      boolean jdkResult = false;
      for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
        jdkResult = jdkPattern.matcher(input).matches();
      }
      long jdkTime = System.nanoTime() - jdkStart;

      // Verify correctness
      assertEquals(jdkResult, dfaResult, "Results don't match for input '" + input + "'");

      dfaTotalTime += dfaTime;
      jdkTotalTime += jdkTime;

      double speedupVsJDK = (double) jdkTime / dfaTime;
      double dfaOpsPerSec = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / dfaTime;
      double jdkOpsPerSec = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / jdkTime;

      System.out.printf(
          "%-10s | Result: %-5s | DFA: %7.2f ms (%10.0f ops/s) | JDK: %7.2f ms (%10.0f ops/s) | vs JDK: %.2fx%n",
          "\"" + input + "\"",
          dfaResult,
          dfaTime / 1_000_000.0,
          dfaOpsPerSec,
          jdkTime / 1_000_000.0,
          jdkOpsPerSec,
          speedupVsJDK);
    }

    // Aggregate
    double speedupVsJDK = (double) jdkTotalTime / dfaTotalTime;
    double dfaAvgOpsPerSec =
        (testInputs.length * BENCHMARK_ITERATIONS * 1_000_000_000.0) / dfaTotalTime;
    double jdkAvgOpsPerSec =
        (testInputs.length * BENCHMARK_ITERATIONS * 1_000_000_000.0) / jdkTotalTime;

    System.out.println();
    System.out.println("=".repeat(80));
    System.out.printf(
        "AGGREGATE  | DFA Total: %7.2f ms (%10.0f ops/s) | JDK Total: %7.2f ms (%10.0f ops/s) | vs JDK: %.2fx%n",
        dfaTotalTime / 1_000_000.0,
        dfaAvgOpsPerSec,
        jdkTotalTime / 1_000_000.0,
        jdkAvgOpsPerSec,
        speedupVsJDK);
    System.out.println("=".repeat(80));
    System.out.println();

    // Note: NFA comparison not included yet - needs integration with annotation processor
    System.out.println("NOTE: This measures DFA prototype vs JDK Pattern.");
    System.out.println(
        "      To compare with Reggie NFA, run AssertionPrototypePerformanceComparison (TBD)");
    System.out.println();

    if (speedupVsJDK >= 2.0) {
      System.out.println(
          "✅ DFA prototype shows " + String.format("%.1fx", speedupVsJDK) + " speedup vs JDK!");
      System.out.println("   This is a strong signal that DFA with assertions is faster than NFA.");
    } else if (speedupVsJDK >= 1.0) {
      System.out.println(
          "⚠️  DFA prototype shows "
              + String.format("%.1fx", speedupVsJDK)
              + " speedup vs JDK (marginal)");
    } else {
      System.out.println(
          "❌ DFA prototype is " + String.format("%.1fx", 1.0 / speedupVsJDK) + " SLOWER than JDK!");
    }
  }

  /** Manually construct DFA for pattern: a(?=bc)bc */
  private DFA buildDFAFor_a_lookaheadBC() {
    Set<NFA.NFAState> emptyNFAStates = Collections.emptySet();

    DFA.DFAState s0 = new DFA.DFAState(0, emptyNFAStates, false);

    List<AssertionCheck> assertions = new ArrayList<>();
    assertions.add(new AssertionCheck(AssertionCheck.Type.POSITIVE_LOOKAHEAD, "bc", 0));
    DFA.DFAState s1 = new DFA.DFAState(1, emptyNFAStates, false, assertions);

    DFA.DFAState s2 = new DFA.DFAState(2, emptyNFAStates, false);
    DFA.DFAState s3 = new DFA.DFAState(3, emptyNFAStates, true);

    s0.addTransition(CharSet.of('a'), s1);
    s1.addTransition(CharSet.of('b'), s2);
    s2.addTransition(CharSet.of('c'), s3);

    List<DFA.DFAState> allStates = Arrays.asList(s0, s1, s2, s3);
    Set<DFA.DFAState> acceptStates = Collections.singleton(s3);

    return new DFA(s0, acceptStates, allStates);
  }

  /** Generate matcher class using ASM bytecode generation. */
  private Class<?> generateMatcher(DFA dfa) throws Exception {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    String className = "DFAPrototypeMatcher_" + System.currentTimeMillis();
    String internalName = className.replace('.', '/');

    cw.visit(
        org.objectweb.asm.Opcodes.V11,
        org.objectweb.asm.Opcodes.ACC_PUBLIC,
        internalName,
        null,
        "java/lang/Object",
        null);

    // Generate default constructor
    org.objectweb.asm.MethodVisitor constructor =
        cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    constructor.visitCode();
    constructor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
    constructor.visitMethodInsn(
        org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    constructor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
    constructor.visitMaxs(0, 0);
    constructor.visitEnd();

    // Generate matches() method
    DFAUnrolledBytecodeGenerator generator = new DFAUnrolledBytecodeGenerator(dfa);
    generator.generateMatchesMethod(cw, internalName);

    cw.visitEnd();
    byte[] bytecode = cw.toByteArray();

    return new ClassLoader() {
      public Class<?> defineClass(String name, byte[] b) {
        return defineClass(name, b, 0, b.length);
      }
    }.defineClass(className, bytecode);
  }
}
