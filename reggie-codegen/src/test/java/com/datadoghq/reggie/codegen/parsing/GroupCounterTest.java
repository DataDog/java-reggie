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
package com.datadoghq.reggie.codegen.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** GroupCounter numbers groups like RegexParser for subroutine, conditional and branch reset. */
class GroupCounterTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "(a)(?1);1",
        "(a)(?R)?;1",
        "(a)(?-1);1",
        "(a)(?+1)(b);2",
        "(?<n>a)(?&n);1",
        "(a)(?(1)b|c);1",
        "(a)(?(1)(b)|c);2",
        "(a)(?(?=b)b|c);1",
        "(?|(a)|(b))(c);2",
        "(?|(a)|(b)(c))(d);3",
        "(?|(a)(?|(b)|(c)(d))|(e));3",
        "(?|(?<x>a)|(?<y>b))(c);2",
        "(a)|(?|x|(y));2",
        "[(?|](a);1",
        "(?|(a)|[|](b)(c));2"
      })
  void countsLikeParser(String pattern, int expected) {
    assertEquals(expected, GroupCounter.count(pattern), pattern);
  }
}
