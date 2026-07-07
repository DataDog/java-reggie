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

import com.datadoghq.reggie.codegen.analysis.PinnedBackreferenceInfo;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Unit test for {@link PinnedBackreferenceBytecodeGenerator}, exercising it directly (not via any
 * strategy dispatcher, since PINNED_BACKREFERENCE routing is wired in a later task).
 *
 * <p>Uses the {@code \b(\w+)\s+\1\b} repeated-word shape as the carrier: group charset WORD,
 * separator charset WHITESPACE - both disjoint by construction, so the boundary is unambiguous.
 *
 * <p>Note on scope: {@code reggie-codegen} does not depend on {@code reggie-runtime}, so the
 * generator's {@code match}/{@code findMatch}/{@code findMatchFrom}/{@code *Bounded} methods (which
 * reference {@code com.datadoghq.reggie.runtime.MatchResultImpl}) can be generated here (bytecode
 * emission needs no runtime classpath) but cannot be class-loaded and executed in this module. This
 * test therefore (a) verifies the full rich-API method set generates without crashing, and (b)
 * executes the boolean {@code matches}/{@code find}/{@code findFrom} methods end-to-end via a
 * separately loaded class that only carries those three methods.
 */
class PinnedBackreferenceBytecodeGeneratorTest {

  private static final class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }

  /** \b(\w+)\s+\1\b - group is WORD, separator is WHITESPACE - both disjoint by construction. */
  private PinnedBackreferenceInfo repeatedWordInfo() {
    return new PinnedBackreferenceInfo(
        1,
        new CharClassNode(CharSet.WORD, false),
        CharSet.WORD,
        1,
        new CharClassNode(CharSet.WHITESPACE, false),
        CharSet.WHITESPACE,
        1,
        -1);
  }

  /** (\w+)\1 with no separator between the group's close and the backreference site. */
  private PinnedBackreferenceInfo noSeparatorInfo() {
    return new PinnedBackreferenceInfo(
        1, new CharClassNode(CharSet.WORD, false), CharSet.WORD, 1, null, null, 0, -1);
  }

  private ClassWriter newClassWriter(String className) {
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
    return cw;
  }

  /**
   * Generates the full rich-API method set (matches this task's completion criterion: same methods
   * {@code FixedRepetitionBackrefBytecodeGenerator} emits) and confirms it doesn't crash.
   */
  @Test
  void generatesFullRichApiMethodSetWithoutCrashing() {
    ClassWriter cw = newClassWriter("PinnedBackrefFullApi");
    PinnedBackreferenceBytecodeGenerator gen =
        new PinnedBackreferenceBytecodeGenerator(repeatedWordInfo(), "PinnedBackrefFullApi");

    assertDoesNotThrow(
        () -> {
          gen.generateMatchesMethod(cw);
          gen.generateFindMethod(cw);
          gen.generateFindFromMethod(cw);
          gen.generateMatchMethod(cw);
          gen.generateMatchesBoundedMethod(cw);
          gen.generateMatchBoundedMethod(cw);
          gen.generateFindMatchMethod(cw);
          gen.generateFindMatchFromMethod(cw);
          cw.visitEnd();
        });

    byte[] bytecode = cw.toByteArray();
    assertNotNull(bytecode);
    assertTrue(bytecode.length > 0);
  }

  /**
   * Same as {@link #generatesFullRichApiMethodSetWithoutCrashing()} but with a no-separator {@link
   * PinnedBackreferenceInfo}, which takes the other side of the {@code hasSeparator()} ternaries
   * that allocate {@code sepStartVar} in every generate*Method (no local slot needed when there's
   * no separator to scan).
   */
  @Test
  void generatesFullRichApiMethodSetWithoutSeparatorWithoutCrashing() {
    ClassWriter cw = newClassWriter("PinnedBackrefNoSeparatorFullApi");
    PinnedBackreferenceBytecodeGenerator gen =
        new PinnedBackreferenceBytecodeGenerator(
            noSeparatorInfo(), "PinnedBackrefNoSeparatorFullApi");

    assertDoesNotThrow(
        () -> {
          gen.generateMatchesMethod(cw);
          gen.generateFindMethod(cw);
          gen.generateFindFromMethod(cw);
          gen.generateMatchMethod(cw);
          gen.generateMatchesBoundedMethod(cw);
          gen.generateMatchBoundedMethod(cw);
          gen.generateFindMatchMethod(cw);
          gen.generateFindMatchFromMethod(cw);
          cw.visitEnd();
        });

    byte[] bytecode = cw.toByteArray();
    assertNotNull(bytecode);
    assertTrue(bytecode.length > 0);
  }

  /**
   * Generates only the boolean matches()/find()/findFrom() methods (no runtime-class dependencies),
   * loads the class, and confirms end-to-end matching behavior.
   */
  private Object compileBooleanApi(PinnedBackreferenceInfo info, String className)
      throws Exception {
    ClassWriter cw = newClassWriter(className);
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

  @Test
  void matchesReturnsTrueForRepeatedWordWithSingleSpace() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher1");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "hello hello"));
  }

  @Test
  void matchesReturnsTrueForRepeatedWordWithMultipleSpaces() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher2");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "foo   foo"));
  }

  @Test
  void matchesReturnsFalseForDifferentWords() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher3");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertFalse((Boolean) matches.invoke(matcher, "foo bar"));
  }

  @Test
  void matchesReturnsFalseForMissingSeparator() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher4");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertFalse((Boolean) matches.invoke(matcher, "foofoo"));
  }

  @Test
  void matchesReturnsFalseForNullInput() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher5");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertFalse((Boolean) matches.invoke(matcher, (String) null));
  }

  @Test
  void findLocatesRepeatedWordWithinLargerText() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher6");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "the quick brown fox fox jumps"));
    assertFalse((Boolean) find.invoke(matcher, "the quick brown fox jumps"));
  }

  @Test
  void findFromReturnsMatchStartPosition() throws Exception {
    Object matcher = compileBooleanApi(repeatedWordInfo(), "RepeatedWordMatcher7");
    Method findFrom = matcher.getClass().getMethod("findFrom", String.class, int.class);
    int pos = (Integer) findFrom.invoke(matcher, "xx hello hello yy", 0);
    assertEquals(3, pos);
  }

  /**
   * Without a separator, the group and the backreference share the same charset (WORD), so the
   * greedy group scan always consumes every following WORD character too - nothing is ever left for
   * the backreference echo to match against. This no-separator shape is structurally unreachable
   * from real pattern detection (a disjoint separator is what makes the boundary unambiguous in the
   * first place), but the generator still must not crash and must never falsely report a match.
   */
  @Test
  void noSeparatorShapeNeverMatchesSinceBoundaryIsAmbiguous() throws Exception {
    Object matcher = compileBooleanApi(noSeparatorInfo(), "NoSeparatorMatcher1");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method findFrom = matcher.getClass().getMethod("findFrom", String.class, int.class);

    assertFalse((Boolean) matches.invoke(matcher, "hellohello"));
    assertFalse((Boolean) matches.invoke(matcher, "hello"));
    assertEquals(-1, (Integer) findFrom.invoke(matcher, "hellohello", 0));
  }
}
