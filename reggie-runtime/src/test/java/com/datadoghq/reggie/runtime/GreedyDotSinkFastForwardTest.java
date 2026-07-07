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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

/**
 * Correctness guard for the greedy dotall-sink fast-forward in {@code
 * PikeVMMatcher.findMatchResultFrom} (see {@code computeSinkGroups}). When the sole surviving
 * thread is parked on a {@code (?s).*} tail with no competing consuming transition, the matcher
 * jumps straight to {@code regionEnd} instead of stepping every remaining character. These tests
 * exercise that path — including inputs long enough that only the fast-forward makes them cheap —
 * and, just as importantly, the cases where the sink must NOT fire (a required suffix or a
 * non-dotall {@code .}) so the eligibility condition cannot silently over-fire and corrupt
 * captures.
 */
public class GreedyDotSinkFastForwardTest {

  private static String longTail(int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) sb.append((char) ('a' + (i % 26)));
    return sb.toString();
  }

  /** Sink fires: (?s)x(.*) over a long tail captures the entire remainder to regionEnd. */
  @Test
  void sinkFires_longDotallTail_capturesToEnd() {
    String tail = longTail(50_000);
    String input = "x" + tail;
    MatchResult r = Reggie.compile("(?s)x(.*)").findMatch(input);
    assertNotNull(r);
    assertEquals(0, r.start(), "match starts at x");
    assertEquals(input.length(), r.end(), "match extends to end of input");
    assertEquals(1, r.start(1), "group(1) starts right after x");
    assertEquals(input.length(), r.end(1), "group(1) ends at regionEnd");
    assertEquals(tail, r.group(1), "group(1) is the whole tail");
  }

  /** Sink with a dotall tail that includes newlines (dotall '.' consumes '\n'). */
  @Test
  void sinkFires_dotallConsumesNewlines() {
    String input = "xline1\nline2\nline3";
    MatchResult r = Reggie.compile("(?s)x(.*)").findMatch(input);
    assertNotNull(r);
    assertEquals(input.length(), r.end(1), "group(1) spans the newlines to end");
    assertEquals("line1\nline2\nline3", r.group(1));
  }

  /** Empty tail: (?s)x(.*) over just "x" yields a zero-length group at regionEnd. */
  @Test
  void sinkFires_emptyTail() {
    MatchResult r = Reggie.compile("(?s)x(.*)").findMatch("x");
    assertNotNull(r);
    assertEquals(1, r.start(1));
    assertEquals(1, r.end(1));
    assertEquals("", r.group(1));
  }

  /** Nested groups both closing in the sink extend to regionEnd. */
  @Test
  void sinkFires_nestedGroups_bothExtendToEnd() {
    String input = "x" + longTail(4_000);
    MatchResult r = Reggie.compile("(?s)x((.*))").findMatch(input);
    assertNotNull(r);
    assertEquals(input.length(), r.end(1), "outer group to end");
    assertEquals(input.length(), r.end(2), "inner group to end");
    assertEquals(1, r.start(1));
    assertEquals(1, r.start(2));
  }

  /**
   * Sink must NOT fire: a required suffix means the greedy .* must give back. If the fast-forward
   * over-fired it would wrongly capture the trailing 'y' and end at regionEnd.
   */
  @Test
  void sinkMustNotFire_requiredSuffix() {
    String input = "x" + longTail(2_000) + "y";
    MatchResult r = Reggie.compile("(?s)x(.*)y").findMatch(input);
    assertNotNull(r);
    assertEquals(input.length(), r.end(), "whole match ends at final y");
    assertEquals(input.length() - 1, r.end(1), "group(1) stops before the final y");
    assertEquals('y', input.charAt(r.end(1)), "char after group(1) is the required y");
  }

  /**
   * Sink must NOT fire for a non-dotall '.': it excludes '\n', so a newline ends the match early.
   * computeSinkGroups requires the full dotall set (including '\n'); a plain '.' must not qualify.
   */
  @Test
  void sinkMustNotFire_nonDotallStopsAtNewline() {
    MatchResult r = Reggie.compile("x(.*)").findMatch("xabc\ndef");
    assertNotNull(r);
    assertEquals("abc", r.group(1), "non-dotall .* stops at the newline");
    assertEquals(4, r.end(1));
  }

  /** Multiline + dotall (COMMAND-shaped tail) still captures correctly to end. */
  @Test
  void sinkFires_multilineDotall() {
    String input = "xhello world\nsecond line";
    MatchResult r = Reggie.compile("(?s)(?m)^x(.*)").findMatch(input);
    assertNotNull(r);
    assertEquals("hello world\nsecond line", r.group(1));
    assertEquals(input.length(), r.end(1));
  }
}
