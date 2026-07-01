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
package com.datadoghq.reggie.codegen.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.V21;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies that {@link BitParallelGlushkovBytecodeGenerator#generateFindFromMethod} emits the
 * correct runtime dispatch:
 *
 * <ul>
 *   <li>{@code findFromWithSkip} for patterns like {@code .*a} where a single ASCII character is
 *       required at every match end.
 *   <li>{@code findFrom} for patterns like {@code .*[abc]} where no single required last character
 *       can be identified.
 * </ul>
 */
class BitParallelGlushkovBytecodeGeneratorTest {

  private static final String RUNTIME_CLASS =
      "com/datadoghq/reggie/runtime/BitParallelGlushkovRuntime";
  private static final String CLASS_NAME = "TestMatcher";

  /** Builds a GlushkovAutomaton for the given pattern. */
  private static GlushkovAutomaton buildGlushkov(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, 0);
    GlushkovAutomaton g = GlushkovAutomaton.from(nfa);
    assertNotNull(g, "Pattern '" + pattern + "' should be eligible for bit-parallel Glushkov");
    return g;
  }

  /**
   * Generates the full class bytecode (static data + findFrom method) for the given generator and
   * returns the names of all INVOKESTATIC targets (method names only) found inside the {@code
   * findFrom} method that target {@code BitParallelGlushkovRuntime}.
   */
  private static List<String> runtimeCallsInFindFrom(BitParallelGlushkovBytecodeGenerator gen)
      throws Exception {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(V21, ACC_PUBLIC, CLASS_NAME, null, "java/lang/Object", null);
    gen.generateStaticData(cw, CLASS_NAME);
    gen.generateFindFromMethod(cw, CLASS_NAME);
    cw.visitEnd();

    byte[] bytecode = cw.toByteArray();

    List<String> found = new ArrayList<>();
    ClassReader cr = new ClassReader(bytecode);
    cr.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!"findFrom".equals(name)) {
              return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
              @Override
              public void visitMethodInsn(
                  int opcode,
                  String owner,
                  String methodName,
                  String methodDescriptor,
                  boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC && RUNTIME_CLASS.equals(owner)) {
                  found.add(methodName);
                }
              }
            };
          }
        },
        0);
    return found;
  }

  /**
   * {@code .*a}: the only accepting position is activated by exactly one ASCII character ('a'), so
   * the generator must emit a call to {@code findFromWithSkip}.
   */
  @Test
  void findFromWithSkip_emittedFor_dotStarLiteralChar() throws Exception {
    GlushkovAutomaton g = buildGlushkov(".*a");
    BitParallelGlushkovBytecodeGenerator gen = new BitParallelGlushkovBytecodeGenerator(g);

    List<String> calls = runtimeCallsInFindFrom(gen);

    assertTrue(
        calls.contains("findFromWithSkip"),
        "Expected findFromWithSkip call for .*a, got: " + calls);
    assertFalse(
        calls.contains("findFrom"), "Should not emit plain findFrom for .*a, got: " + calls);
  }

  /**
   * {@code .*[abc]}: the accepting position can be reached by 'a', 'b', or 'c', so no single
   * required character exists and the generator must emit a call to {@code findFrom}.
   */
  @Test
  void findFrom_emittedFor_dotStarCharClass() throws Exception {
    GlushkovAutomaton g = buildGlushkov(".*[abc]");
    BitParallelGlushkovBytecodeGenerator gen = new BitParallelGlushkovBytecodeGenerator(g);

    List<String> calls = runtimeCallsInFindFrom(gen);

    assertTrue(calls.contains("findFrom"), "Expected findFrom call for .*[abc], got: " + calls);
    assertFalse(
        calls.contains("findFromWithSkip"),
        "Should not emit findFromWithSkip for .*[abc], got: " + calls);
  }
}
