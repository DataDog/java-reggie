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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.junit.jupiter.api.Test;

/** Direct unit tests for the PinnedBackreferenceInfo PatternInfo carrier. */
class PinnedBackreferenceInfoTest {

  private static RegexNode bodyNode(CharSet charSet) {
    return new CharClassNode(charSet, false);
  }

  @Test
  void structuralHashCodeDiffersOnGroupIndexAndCharSet() {
    PinnedBackreferenceInfo a =
        new PinnedBackreferenceInfo(1, bodyNode(CharSet.WORD), CharSet.WORD, null, null);
    PinnedBackreferenceInfo b =
        new PinnedBackreferenceInfo(2, bodyNode(CharSet.DIGIT), CharSet.DIGIT, null, null);

    assertNotEquals(a.structuralHashCode(), b.structuralHashCode());
  }

  @Test
  void hasSeparatorReflectsConstructorArgument() {
    PinnedBackreferenceInfo noSeparator =
        new PinnedBackreferenceInfo(1, bodyNode(CharSet.WORD), CharSet.WORD, null, null);
    assertFalse(noSeparator.hasSeparator());

    RegexNode separatorNode = bodyNode(CharSet.WHITESPACE);
    PinnedBackreferenceInfo withSeparator =
        new PinnedBackreferenceInfo(
            1, bodyNode(CharSet.WORD), CharSet.WORD, separatorNode, CharSet.WHITESPACE);
    assertTrue(withSeparator.hasSeparator());
  }
}
