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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.Test;

class GraphemeProfileTest {
  @Test
  void specialisedLtsApiKeepsItsAdmissionRules() {
    assertEquals(
        ReggieCompilationRejection.PARSE_FAILURE,
        ReggieCompiledPattern.tryCompile(new ReggieCompileRequest("\\X", ReggieCompileFlag.NONE))
            .rejection());
  }

  @Test
  void supportedProfilesAreGa21Through27() {
    for (int feature = 8; feature <= 30; feature++) {
      assertEquals(feature >= 21 && feature <= 27, GraphemeData.supported(feature, false));
      assertFalse(GraphemeData.supported(feature, true));
    }
  }

  @Test
  void executingProfileSelectsNativeOrExplicitFallback() {
    System.out.println(
        "Grapheme profile: "
            + Runtime.version()
            + "; "
            + System.getProperty("java.vendor")
            + "; "
            + System.getProperty("java.home"));
    boolean supported =
        GraphemeData.supported(Runtime.version().feature(), Runtime.version().pre().isPresent());
    if (supported) {
      assertInstanceOf(GraphemeMatcher.class, Reggie.compile("\\X"));
    } else {
      assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("\\X"));
      assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("\\X"));
    }
    var matcher = Reggie.compileAllowingFallback("\\X");
    assertEquals(!supported, matcher.isJdkFallback());
    assertTrue(matcher.matches("e\u0301"));
  }
}
