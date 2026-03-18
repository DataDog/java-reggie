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

import static org.objectweb.asm.Opcodes.*;

import java.util.List;
import org.objectweb.asm.*;

/**
 * Generates bytecode for implementation classes with lazy-initialized matchers using ASM. Example:
 * MyPatterns$Impl extends MyPatterns with double-check locking.
 */
public class ImplClassBytecodeGenerator {

  private final String packageName;
  private final String superClassName;
  private final String implClassName;
  private final List<MethodInfo> methods;

  public static class MethodInfo {
    public final String methodName;
    public final String matcherClassName;

    public MethodInfo(String methodName, String matcherClassName) {
      this.methodName = methodName;
      this.matcherClassName = matcherClassName;
    }
  }

  public ImplClassBytecodeGenerator(
      String packageName, String className, List<MethodInfo> methods) {
    this.packageName = packageName.replace('.', '/');
    this.superClassName = this.packageName + "/" + className;
    this.implClassName = superClassName + "$Impl";
    this.methods = methods;
  }

  /** Generate the complete bytecode for the implementation class. */
  public byte[] generate() {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    // Class declaration: public final class XXX$Impl extends XXX implements ReggiePatterns
    cw.visit(
        V21,
        ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
        implClassName,
        null,
        superClassName,
        new String[] {"com/datadoghq/reggie/ReggiePatterns"});

    // Generate fields for each matcher (volatile)
    for (MethodInfo method : methods) {
      String fieldDescriptor = "L" + packageName + "/" + method.matcherClassName + ";";
      cw.visitField(ACC_PRIVATE | ACC_VOLATILE, method.methodName, fieldDescriptor, null, null)
          .visitEnd();
    }

    // Generate default constructor
    generateConstructor(cw);

    // Generate method implementations with lazy initialization
    for (MethodInfo method : methods) {
      generateLazyInitMethod(cw, method);
    }

    cw.visitEnd();
    return cw.toByteArray();
  }

  /** Generate default constructor: public XXX$Impl() { super(); } */
  private void generateConstructor(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();

    // Load 'this'
    mv.visitVarInsn(ALOAD, 0);

    // Call super()
    mv.visitMethodInsn(INVOKESPECIAL, superClassName, "<init>", "()V", false);

    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate method with lazy initialization and double-check locking. Pattern: public
   * ReggieMatcher methodName() { if (field == null) { synchronized(this) { if (field == null) {
   * field = new MatcherClass(); } } } return field; }
   */
  private void generateLazyInitMethod(ClassWriter cw, MethodInfo method) {
    String matcherClassFullName = packageName + "/" + method.matcherClassName;
    String fieldDescriptor = "L" + matcherClassFullName + ";";

    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            method.methodName,
            "()Lcom/datadoghq/reggie/runtime/ReggieMatcher;",
            null,
            null);
    mv.visitCode();

    Label alreadyInitialized = new Label();
    Label syncStart = new Label();
    Label syncEnd = new Label();
    Label afterSync = new Label();

    // First check: if (field == null)
    mv.visitVarInsn(ALOAD, 0); // Load 'this'
    mv.visitFieldInsn(GETFIELD, implClassName, method.methodName, fieldDescriptor);
    mv.visitJumpInsn(IFNONNULL, alreadyInitialized);

    // Enter synchronized block
    mv.visitVarInsn(ALOAD, 0); // Load 'this' for monitor
    mv.visitInsn(DUP);
    mv.visitVarInsn(ASTORE, 1); // Store monitor object in local var 1
    mv.visitInsn(MONITORENTER);

    mv.visitLabel(syncStart);

    // Second check inside synchronized: if (field == null)
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, implClassName, method.methodName, fieldDescriptor);
    mv.visitJumpInsn(IFNONNULL, syncEnd);

    // Initialize: field = new MatcherClass();
    mv.visitVarInsn(ALOAD, 0); // Load 'this'
    mv.visitTypeInsn(NEW, matcherClassFullName);
    mv.visitInsn(DUP);
    mv.visitMethodInsn(INVOKESPECIAL, matcherClassFullName, "<init>", "()V", false);
    mv.visitFieldInsn(PUTFIELD, implClassName, method.methodName, fieldDescriptor);

    mv.visitLabel(syncEnd);

    // Exit synchronized block
    mv.visitVarInsn(ALOAD, 1); // Load monitor object
    mv.visitInsn(MONITOREXIT);
    mv.visitJumpInsn(GOTO, afterSync);

    // Exception handler for synchronized block
    Label exceptionHandler = new Label();
    mv.visitLabel(exceptionHandler);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(MONITOREXIT);
    mv.visitInsn(ATHROW);

    mv.visitLabel(afterSync);

    // Return field
    mv.visitLabel(alreadyInitialized);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, implClassName, method.methodName, fieldDescriptor);
    mv.visitInsn(ARETURN);

    // Add exception table for synchronized block
    mv.visitTryCatchBlock(syncStart, exceptionHandler, exceptionHandler, null);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  private String capitalizeFirst(String s) {
    if (s == null || s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
