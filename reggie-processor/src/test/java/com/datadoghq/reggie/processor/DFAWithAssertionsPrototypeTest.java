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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.automaton.AssertionCheck;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.codegen.DFAUnrolledBytecodeGenerator;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

/**
 * Prototype test for DFA with assertion support. Tests pattern: a(?=bc)bc - "match 'a', check 'bc'
 * follows (lookahead), then consume 'bc'"
 */
public class DFAWithAssertionsPrototypeTest {

  @Test
  public void testSimpleLookahead_abc_matches() throws Exception {
    DFA dfa = buildDFAFor_a_lookaheadBC();
    Class<?> matcherClass = generateMatcher(dfa);
    Object matcher = matcherClass.getDeclaredConstructor().newInstance();
    Method matches = matcherClass.getMethod("matches", String.class);

    // "abc" should MATCH: 'a' at 0, lookahead sees "bc" at 1-2, then consumes "bc"
    Boolean result = (Boolean) matches.invoke(matcher, "abc");
    assertTrue(result, "Pattern a(?=bc)bc should match 'abc'");
  }

  @Test
  public void testSimpleLookahead_adc_noMatch() throws Exception {
    DFA dfa = buildDFAFor_a_lookaheadBC();
    Class<?> matcherClass = generateMatcher(dfa);
    Object matcher = matcherClass.getDeclaredConstructor().newInstance();
    Method matches = matcherClass.getMethod("matches", String.class);

    // "adc" should NOT MATCH: 'a' at 0, but lookahead at 1-2 finds "dc" not "bc"
    Boolean result = (Boolean) matches.invoke(matcher, "adc");
    assertFalse(result, "Pattern a(?=bc)bc should NOT match 'adc' (lookahead fails)");
  }

  @Test
  public void testSimpleLookahead_a_noMatch() throws Exception {
    DFA dfa = buildDFAFor_a_lookaheadBC();
    Class<?> matcherClass = generateMatcher(dfa);
    Object matcher = matcherClass.getDeclaredConstructor().newInstance();
    Method matches = matcherClass.getMethod("matches", String.class);

    // "a" alone should NOT MATCH: 'a' at 0, but EOF at 1 (lookahead fails, needs "bc")
    Boolean result = (Boolean) matches.invoke(matcher, "a");
    assertFalse(result, "Pattern a(?=bc)bc should NOT match 'a' alone (lookahead fails, EOF)");
  }

  @Test
  public void testSimpleLookahead_ab_noMatch() throws Exception {
    DFA dfa = buildDFAFor_a_lookaheadBC();
    Class<?> matcherClass = generateMatcher(dfa);
    Object matcher = matcherClass.getDeclaredConstructor().newInstance();
    Method matches = matcherClass.getMethod("matches", String.class);

    // "ab" should NOT MATCH: 'a' at 0, lookahead at 1 only finds 'b' not "bc" (EOF at 2)
    Boolean result = (Boolean) matches.invoke(matcher, "ab");
    assertFalse(result, "Pattern a(?=bc)bc should NOT match 'ab' (lookahead fails, incomplete)");
  }

  /**
   * Manually construct DFA for pattern: a(?=bc)bc
   *
   * <p>States: S0: start S1: saw 'a', check lookahead for "bc" S2: lookahead passed, consume 'b'
   * S3: consume 'c' S4: accept
   *
   * <p>Transitions: S0 --'a'--> S1 S1 [assertion: peek "bc" at +0], then --'b'--> S2 S2 --'c'--> S3
   * (accepting)
   */
  private DFA buildDFAFor_a_lookaheadBC() {
    // Create empty NFA states (just for structure, not used in execution)
    Set<NFA.NFAState> emptyNFAStates = Collections.emptySet();

    // State 0: start
    DFA.DFAState s0 = new DFA.DFAState(0, emptyNFAStates, false);

    // State 1: saw 'a', has lookahead assertion for "bc"
    List<AssertionCheck> assertions = new ArrayList<>();
    assertions.add(
        new AssertionCheck(
            AssertionCheck.Type.POSITIVE_LOOKAHEAD,
            "bc", // check for "bc"
            0 // at current position (after consuming 'a')
            ));
    DFA.DFAState s1 = new DFA.DFAState(1, emptyNFAStates, false, assertions);

    // State 2: lookahead passed, saw 'b'
    DFA.DFAState s2 = new DFA.DFAState(2, emptyNFAStates, false);

    // State 3: saw 'c', accept
    DFA.DFAState s3 = new DFA.DFAState(3, emptyNFAStates, true);

    // Add transitions
    s0.addTransition(CharSet.of('a'), s1);
    s1.addTransition(CharSet.of('b'), s2); // After assertion passes, consume 'b'
    s2.addTransition(CharSet.of('c'), s3); // Then consume 'c'

    List<DFA.DFAState> allStates = Arrays.asList(s0, s1, s2, s3);
    Set<DFA.DFAState> acceptStates = Collections.singleton(s3);

    return new DFA(s0, acceptStates, allStates);
  }

  /** Generate matcher class using ASM bytecode generation. */
  private Class<?> generateMatcher(DFA dfa) throws Exception {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    String className = "TestMatcher_" + System.currentTimeMillis();
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

    // Generate matches() method using modified DFAUnrolledBytecodeGenerator
    DFAUnrolledBytecodeGenerator generator = new DFAUnrolledBytecodeGenerator(dfa);
    generator.generateMatchesMethod(cw, internalName);

    cw.visitEnd();
    byte[] bytecode = cw.toByteArray();

    // Load class
    return new ClassLoader() {
      public Class<?> defineClass(String name, byte[] b) {
        return defineClass(name, b, 0, b.length);
      }
    }.defineClass(className, bytecode);
  }
}
