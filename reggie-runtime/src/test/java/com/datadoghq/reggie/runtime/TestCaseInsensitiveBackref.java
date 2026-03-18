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

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

public class TestCaseInsensitiveBackref {
  @Test
  void testCaseInsensitiveBackref() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(?i)(ab)\\d\\1");

    System.out.println("Pattern: (?i)(ab)\\d\\1");
    System.out.println("Testing 'Ab4ab': " + m.matches("Ab4ab"));
    System.out.println("Testing 'AB4ab': " + m.matches("AB4ab"));
    System.out.println("Testing 'ab4AB': " + m.matches("ab4AB"));
    System.out.println("Testing 'ab4ab': " + m.matches("ab4ab"));

    assertTrue(m.matches("Ab4ab"), "Should match 'Ab4ab'");
    assertTrue(m.matches("AB4ab"), "Should match 'AB4ab'");
    assertTrue(m.matches("ab4AB"), "Should match 'ab4AB'");
    assertTrue(m.matches("ab4ab"), "Should match 'ab4ab'");
  }
}
