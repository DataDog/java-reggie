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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.CapturePolicy;
import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.Test;

class LinearTemplateAccessLogTest {
  private static final ReggieOptions NAMED_ONLY =
      ReggieOptions.builder().capturePolicy(CapturePolicy.NAMED_ONLY).build();

  private static final String COMBINED_ACCESS_LOG_PATTERN =
      "(?s)(?<grok0>[0-9A-Fa-f:.]+) (?<grok1>\\S+) (?<grok2>\\S+) "
          + "\\[(?<grok3>[^\\]]+)\\]\\s+\"(?<grok4>\\b\\w+\\b) (?<grok5>\\S+) HTTP/(?<grok6>\\d+\\.\\d+)\" "
          + "(?<grok7>[+-]?\\d+) (?<grok8>[+-]?\\d+) "
          + "\"(?<grok9>\\S+)\" \"(?<grok10>[^\\\"]*)\" \"(?<grok11>[^\\\"]*)\" \"(?<grok12>[^\\\"]*)\" "
          + "(?<grok13>[+-]?\\d+(?:\\.\\d+)?) (?<grok14>[+-]?\\d+(?:\\.\\d+)?).* "
          + "\\[(?<grok15>\\b\\w+\\b)\\] .*";

  @Test
  void matchesCombinedAccessLogWithDelimiterAwareCaptures() {
    ReggieMatcher matcher = Reggie.compile(COMBINED_ACCESS_LOG_PATTERN, NAMED_ONLY);
    String input =
        "10.202.82.195 - - [15/Mar/2019:19:45:35 -0700]  \"POST /config?x=y HTTP/1.1\" "
            + "200 17888 \"https://example.com/index.html\" \"Mozilla/5.0 Test\" \"-\" "
            + "\"tracking-id\" 0.024 0.024 . [nginx_access]  [not_the_logger]";

    int[] starts = new int[17];
    int[] ends = new int[17];
    assertTrue(matcher.matchInto(input, starts, ends));

    assertGroup(input, starts, ends, 1, "10.202.82.195");
    assertGroup(input, starts, ends, 4, "15/Mar/2019:19:45:35 -0700");
    assertGroup(input, starts, ends, 5, "POST");
    assertGroup(input, starts, ends, 6, "/config?x=y");
    assertGroup(input, starts, ends, 7, "1.1");
    assertGroup(input, starts, ends, 8, "200");
    assertGroup(input, starts, ends, 9, "17888");
    assertGroup(input, starts, ends, 10, "https://example.com/index.html");
    assertGroup(input, starts, ends, 11, "Mozilla/5.0 Test");
    assertGroup(input, starts, ends, 12, "-");
    assertGroup(input, starts, ends, 13, "tracking-id");
    assertGroup(input, starts, ends, 14, "0.024");
    assertGroup(input, starts, ends, 15, "0.024");
    assertGroup(input, starts, ends, 16, "nginx_access");
  }

  @Test
  void leavesCallerArraysUnchangedOnNoMatch() {
    ReggieMatcher matcher = Reggie.compile(COMBINED_ACCESS_LOG_PATTERN, NAMED_ONLY);
    int[] starts = new int[17];
    int[] ends = new int[17];
    starts[1] = 123;
    ends[1] = 456;

    assertFalse(matcher.matchInto("not an access log", starts, ends));

    assertEquals(123, starts[1]);
    assertEquals(456, ends[1]);
  }

  private static void assertGroup(String input, int[] starts, int[] ends, int group, String value) {
    assertEquals(value, input.substring(starts[group], ends[group]));
  }
}
