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

import static com.datadoghq.reggie.runtime.GraphemeData.CONSONANT;
import static com.datadoghq.reggie.runtime.GraphemeData.CONTROL;
import static com.datadoghq.reggie.runtime.GraphemeData.CR;
import static com.datadoghq.reggie.runtime.GraphemeData.EXTEND;
import static com.datadoghq.reggie.runtime.GraphemeData.EXTENDED_PICTOGRAPHIC;
import static com.datadoghq.reggie.runtime.GraphemeData.INDIC_EXTEND;
import static com.datadoghq.reggie.runtime.GraphemeData.L;
import static com.datadoghq.reggie.runtime.GraphemeData.LF;
import static com.datadoghq.reggie.runtime.GraphemeData.LINKER;
import static com.datadoghq.reggie.runtime.GraphemeData.LV;
import static com.datadoghq.reggie.runtime.GraphemeData.LVT;
import static com.datadoghq.reggie.runtime.GraphemeData.PREPEND;
import static com.datadoghq.reggie.runtime.GraphemeData.RI;
import static com.datadoghq.reggie.runtime.GraphemeData.SPACINGMARK;
import static com.datadoghq.reggie.runtime.GraphemeData.T;
import static com.datadoghq.reggie.runtime.GraphemeData.V;
import static com.datadoghq.reggie.runtime.GraphemeData.ZWJ;

/** Forward extended-grapheme scan, with UTF-16 offsets and executing-JDK compatibility rules. */
final class GraphemeScanner {
  private GraphemeScanner() {}

  static int nextEnd(CharSequence input, int start, int limit) {
    if (input instanceof String text) return nextEnd(text, start, limit);
    return scan(input, start, limit);
  }

  static int nextEnd(String input, int start, int limit) {
    char first = input.charAt(start);
    if (first < 0x7f) {
      if (first == '\r')
        return start + 1 < limit && input.charAt(start + 1) == '\n' ? start + 2 : start + 1;
      if (first < 0x20 || start + 1 == limit || input.charAt(start + 1) < 0x7f) return start + 1;
    }
    return scan(input, start, limit);
  }

  private static int scan(CharSequence input, int start, int limit) {
    // The JDK decodes split surrogate pairs against the original input, beyond a region end.
    int previousCodePoint = Character.codePointAt(input, start);
    int previous = GraphemeData.type(previousCodePoint);
    int end = start + Character.charCount(previousCodePoint);
    // The host regex keeps GB11 context from the first code point of this match.
    boolean pictographicStart = previous == EXTENDED_PICTOGRAPHIC;
    boolean oddRegionalIndicators = previous == RI;
    while (end < limit) {
      if (GraphemeData.indicConjunctBreak() && GraphemeData.isConsonant(previousCodePoint)) {
        int conjunctEnd = conjunctEnd(input, end, limit);
        if (conjunctEnd > end) {
          end = conjunctEnd;
          continue;
        }
      }
      int codePoint = Character.codePointAt(input, end);
      int current = GraphemeData.type(codePoint);
      boolean emojiJoin = pictographicStart && previous == ZWJ && current == EXTENDED_PICTOGRAPHIC;
      boolean regionalJoin = oddRegionalIndicators && previous == RI && current == RI;
      if (!emojiJoin && !regionalJoin && breaks(previous, current)) break;
      if (current == RI) oddRegionalIndicators = !oddRegionalIndicators;
      previous = current;
      previousCodePoint = codePoint;
      end += Character.charCount(codePoint);
    }
    return end;
  }

  private static int conjunctEnd(CharSequence input, int start, int limit) {
    boolean linker = false;
    int end = start;
    while (end < limit) {
      int codePoint = Character.codePointAt(input, end);
      int properties = GraphemeData.indic(codePoint);
      end += Character.charCount(codePoint);
      if ((properties & LINKER) != 0) linker = true;
      else if ((properties & CONSONANT) != 0) return linker ? end : -1;
      else if ((properties & INDIC_EXTEND) == 0) return -1;
    }
    return -1;
  }

  private static boolean breaks(int previous, int current) {
    if (previous == CR && current == LF) return false;
    if (previous >= CR && previous <= CONTROL || current >= CR && current <= CONTROL) return true;
    if (previous == L && (current == L || current == V || current == LV || current == LVT))
      return false;
    if ((previous == LV || previous == V) && (current == V || current == T)) return false;
    if ((previous == LVT || previous == T) && current == T) return false;
    return current != EXTEND && current != ZWJ && current != SPACINGMARK && previous != PREPEND;
  }
}
