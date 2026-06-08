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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class ScopedFlagQuickTest {
  @Test
  void scopedCaseInsensitive() {
    // Scoped (?i:...) - only 'b' is case-insensitive
    var jdk = Pattern.compile("a(?i:b)c").matcher("aBc");
    var reg = Reggie.compile("a(?i:b)c");
    assertEquals(jdk.matches(), reg.matches("aBc"), "aBc");
    assertEquals(
        Pattern.compile("a(?i:b)c").matcher("ABC").matches(),
        reg.matches("ABC"),
        "ABC should not match");
    assertEquals(
        Pattern.compile("(?i:abc)").matcher("ABC").matches(),
        Reggie.compile("(?i:abc)").matches("ABC"),
        "full scope");
    System.out.println("aBc: " + reg.matches("aBc"));
    System.out.println("ABC: " + reg.matches("ABC"));
    System.out.println("(?i:abc).matches(ABC): " + Reggie.compile("(?i:abc)").matches("ABC"));
  }
}
