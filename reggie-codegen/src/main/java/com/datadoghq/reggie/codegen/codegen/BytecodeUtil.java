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

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.MethodVisitor;

/** Utility methods for bytecode generation. */
public class BytecodeUtil {

  /**
   * Push an integer constant onto the stack efficiently. Avoids autoboxing issues with
   * visitLdcInsn().
   *
   * @param mv the method visitor
   * @param value the integer value to push
   */
  public static void pushInt(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      // Use ICONST_X for small values
      mv.visitInsn(ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }
}
