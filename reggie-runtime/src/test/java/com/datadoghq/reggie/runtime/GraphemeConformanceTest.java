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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadoghq.reggie.Reggie;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphemeConformanceTest {
  private static final Pattern JDK = Pattern.compile("\\X");

  @BeforeEach
  void requireNativeProfile() {
    assumeTrue(
        GraphemeData.supported(Runtime.version().feature(), Runtime.version().pre().isPresent()));
  }

  @Test
  void unicodeFixturesAndExecutingJdk() throws Exception {
    int feature = Runtime.version().feature();
    String version =
        feature == 21 ? "15.0.0" : feature <= 23 ? "15.1.0" : feature <= 25 ? "16.0.0" : "17.0.0";
    String resource = "/grapheme/GraphemeBreakTest-" + version + ".txt";
    var matcher = Reggie.compile("\\X");
    List<Integer> deviations = new ArrayList<>();
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                getClass().getResourceAsStream(resource), StandardCharsets.UTF_8))) {
      int lineNumber = 0;
      for (String line; (line = reader.readLine()) != null; ) {
        lineNumber++;
        String data = line.split("#", 2)[0].strip();
        if (data.isEmpty()) continue;
        StringBuilder input = new StringBuilder();
        List<Integer> expected = new ArrayList<>();
        for (String token : data.split("\\s+")) {
          if (token.equals("÷")) expected.add(input.length());
          else if (!token.equals("×")) input.appendCodePoint(Integer.parseInt(token, 16));
        }
        String text = input.toString();
        List<Integer> oracle = new ArrayList<>(List.of(0));
        var jdk = JDK.matcher(text);
        while (jdk.find()) oracle.add(jdk.end());
        List<Integer> actual = new ArrayList<>(List.of(0));
        int[] bounds = new int[2];
        for (int from = 0; matcher.findBoundsFrom(text, from, bounds); from = bounds[1])
          actual.add(bounds[1]);
        assertEquals(oracle, actual, resource + ":" + lineNumber);
        if (!expected.equals(oracle)) deviations.add(lineNumber);
      }
    }
    // JDK compatibility exceptions must be recorded individually when the versioned corpus changes.
    assertEquals(List.of(), deviations, "Unicode/JDK deviations in " + resource);
  }

  @Test
  void everyCodePointInBoundaryContexts() {
    var matcher = Reggie.compile("\\X");
    var oracle = JDK.matcher("");
    int[] contexts = {
      0x61, 0xd, 0xa, 0, 0x301, 0x200d, 0x1f1e6, 0x600, 0x903, 0x1100, 0x1161, 0x11a8, 0xac00,
      0xac01, 0x1f469, 0x915, 0x94d
    };
    StringBuilder text = new StringBuilder(4);
    int[] bounds = new int[2];
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      int count =
          Character.getType(cp) == Character.UNASSIGNED && !Character.isExtendedPictographic(cp)
              ? 1
              : contexts.length;
      for (int index = 0; index < count; index++) {
        int context = count == 1 ? 0x301 : contexts[index];
        for (int direction = 0; direction < 2; direction++) {
          boolean reverse = direction != 0;
          text.setLength(0);
          text.appendCodePoint(reverse ? context : cp).appendCodePoint(reverse ? cp : context);
          String input = text.toString();
          oracle.reset(input);
          assertTrue(oracle.find());
          assertTrue(matcher.findBoundsFrom(input, 0, bounds));
          if (oracle.end() != bounds[1])
            fail(
                "cp="
                    + Integer.toHexString(cp)
                    + " context="
                    + Integer.toHexString(context)
                    + " reverse="
                    + reverse
                    + " jdk="
                    + oracle.end()
                    + " native="
                    + bounds[1]);
        }
      }
    }
  }
}
