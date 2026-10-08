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

import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** GreedySuffixInfo detection boundaries and structural hash. */
class GreedySuffixInfoTest {

  private static GreedySuffixInfo detect(String pattern) throws Exception {
    return GreedySuffixInfo.detect(new RegexParser().parse(pattern));
  }

  @Test
  void declinesShapesOutsideThePlan() throws Exception {
    String[] declined = {
      "(.*)",
      "(.*)\\b",
      "(.*)$",
      "(.*?)bar",
      "(.*+)bar",
      "(?>.*)bar",
      "(.{0,5})bar",
      "(.{2,})bar",
      "([a-z]*)bar",
      "(.*)(\\d+?)",
      "(.*)(\\d++)",
      "(.*)(\\d{0})",
      "(.*)(?<d>\\d+)",
      "(?<g>.*)bar",
      "(.*)(\\d+)(x)",
      "(.*)(\\d+)x",
      "(.*)ab(\\d+)",
      "(.*)(\\d+)\\b",
      "(.*)([\\d\\n]+)$",
      "(.*)([\\d\\u2028]+)\\z",
      "(.*)(?:ab)",
      "(.*)a|b",
      "^(.*)bar",
      "\\A(.*)bar",
      "(.*)\\Bbar",
      "(.*)bar\\K",
      "(?m)(.*)bar$",
      "(x)(.*)bar",
      "(?:x)(.*)bar",
      "x*(.*)bar",
      "(.*)(?=a)b",
      "(a)(.*)\\1"
    };
    List<String> accepted = new ArrayList<>();
    for (String p : declined) {
      if (detect(p) != null) accepted.add(p);
    }
    assertEquals(List.of(), accepted);
  }

  @Test
  void acceptsShapesInThePlan() throws Exception {
    String[] accepted = {
      "(.*)bar",
      "(.+)bar",
      "(?s)(.*)bar",
      "x[a1](.*)\\bbar",
      "(.*)(\\d+)",
      "(.*)(\\d*)$",
      "(.*)(a{1,3})\\Z",
      "(.*)(\\d+)\\z",
      "(.*)([\\d\\n]+)",
      "(.*)\\n$"
    };
    List<String> declined = new ArrayList<>();
    for (String p : accepted) {
      if (detect(p) == null) declined.add(p);
    }
    assertEquals(List.of(), declined);
  }

  @Test
  void structuralHashCodeDiffersOnEveryField() throws Exception {
    String[] distinct = {
      "(.*)bar",
      "x(.*)bar",
      "(?s)(.*)bar",
      "(.+)bar",
      "(.*)\\bbar",
      "(.*)baz",
      "(.*)bar$",
      "(.*)bar\\z",
      "(.*)(\\d+)",
      "(.*)(\\w+)",
      "(.*)(\\d*)",
      "(.*)(\\d{1,3})"
    };
    List<String> collisions = new ArrayList<>();
    for (int i = 0; i < distinct.length; i++) {
      for (int j = i + 1; j < distinct.length; j++) {
        if (detect(distinct[i]).structuralHashCode() == detect(distinct[j]).structuralHashCode()) {
          collisions.add(distinct[i] + " / " + distinct[j]);
        }
      }
    }
    assertEquals(List.of(), collisions);
    assertEquals(detect("(.*)bar").structuralHashCode(), detect("(.*)bar").structuralHashCode());
  }
}
