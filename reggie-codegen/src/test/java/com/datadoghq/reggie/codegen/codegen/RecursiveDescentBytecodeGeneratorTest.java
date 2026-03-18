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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

/**
 * Tests for RecursiveDescentBytecodeGenerator. Phase 3: Basic framework tests - verify generated
 * bytecode is valid. Phase 4-7: Will add functionality tests as parsers are implemented.
 */
class RecursiveDescentBytecodeGeneratorTest {

  /** Helper to create generator for a pattern. */
  private RecursiveDescentBytecodeGenerator createGenerator(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);

    // For recursive descent patterns, ThompsonBuilder will throw UnsupportedOperationException
    // Pass null NFA since we don't need it for Phase 3 tests
    return new RecursiveDescentBytecodeGenerator(ast, null);
  }

  @Test
  void testGeneratorCreation_Subroutine() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("a(?R)b");
    assertNotNull(generator, "Generator should be created for subroutine pattern");
  }

  @Test
  void testGeneratorCreation_Conditional() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(a)?(?(1)b|c)");
    assertNotNull(generator, "Generator should be created for conditional pattern");
  }

  @Test
  void testGeneratorCreation_BranchReset() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(?|abc|xyz)");
    assertNotNull(generator, "Generator should be created for branch reset pattern");
  }

  @Test
  void testGenerateMatchesMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("a(?R)b");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    // Should generate without errors
    assertDoesNotThrow(
        () -> {
          generator.generateMatchesMethod(cw, "TestMatcher");
        },
        "generateMatchesMethod should not throw");

    // Verify bytecode is valid (no VerifyError)
    byte[] bytecode = cw.toByteArray();
    assertTrue(bytecode.length > 0, "Bytecode should be generated");
  }

  @Test
  void testGenerateFindMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(a)?(?(1)b)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateFindMethod(cw, "TestMatcher");
        },
        "generateFindMethod should not throw");
  }

  @Test
  void testGenerateFindFromMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(?|a|b)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateFindFromMethod(cw, "TestMatcher");
        },
        "generateFindFromMethod should not throw");
  }

  @Test
  void testGenerateFindBoundsFromMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("a(?R)b");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateFindBoundsFromMethod(cw, "TestMatcher");
        },
        "generateFindBoundsFromMethod should not throw");
  }

  @Test
  void testGenerateMatchMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(a)?(?(1)b|c)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateMatchMethod(cw, "TestMatcher");
        },
        "generateMatchMethod should not throw");
  }

  @Test
  void testGenerateMatchesBoundedMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(?|abc|xyz)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateMatchesBoundedMethod(cw, "TestMatcher");
        },
        "generateMatchesBoundedMethod should not throw");
  }

  @Test
  void testGenerateMatchBoundedMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("a(?R)b");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateMatchesBoundedMethod(cw, "TestMatcher");
        },
        "generateMatchesBoundedMethod should not throw");
  }

  @Test
  void testGenerateFindMatchMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(a)?(?(1)b)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateFindMatchMethod(cw, "TestMatcher");
        },
        "generateFindMatchMethod should not throw");
  }

  @Test
  void testGenerateFindMatchFromMethod() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(?|a|b)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    assertDoesNotThrow(
        () -> {
          generator.generateFindMatchFromMethod(cw, "TestMatcher");
        },
        "generateFindMatchFromMethod should not throw");
  }

  @Test
  void testGenerateParserMethods() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("a(?R)b");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    // Phase 3: generateAllParserMethods is placeholder
    // Will implement in Phase 4-7
    assertDoesNotThrow(
        () -> {
          generator.generateAllParserMethods(cw, "TestMatcher");
        },
        "generateAllParserMethods should not throw");
  }

  @Test
  void testMultipleMethodGeneration() throws Exception {
    RecursiveDescentBytecodeGenerator generator = createGenerator("(a)?(?(1)b|c)");

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
        "TestMatcher",
        null,
        "java/lang/Object",
        null);

    // Generate methods without errors (only methods that exist in current API)
    assertDoesNotThrow(
        () -> {
          generator.generateMatchesMethod(cw, "TestMatcher");
          generator.generateFindBoundsFromMethod(cw, "TestMatcher");
          generator.generateMatchesBoundedMethod(cw, "TestMatcher");
          generator.generateMatchMethod(cw, "TestMatcher");
        },
        "Multiple method generation should work");

    byte[] bytecode = cw.toByteArray();
    assertTrue(bytecode.length > 0, "Bytecode should be generated");
  }
}
