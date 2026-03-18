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
package com.datadoghq.reggie.codegen.codegen;

import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.codegen.swar.*;
import java.util.List;

/**
 * Analyzes character set patterns at compile time to determine if SWAR optimization is beneficial.
 *
 * <p>This class implements conservative heuristics - it only returns an optimization when we have
 * strong evidence that SWAR will improve performance. When in doubt, returns null to use the
 * standard charAt() loop.
 *
 * <p>The optimization decision is made ONCE at bytecode generation time, resulting in specialized
 * code with zero runtime overhead.
 */
public final class SWARPatternAnalyzer {

  private SWARPatternAnalyzer() {
    // Utility class
  }

  /**
   * Analyze a CharSet and determine if SWAR optimization is beneficial.
   *
   * @param charset the character set to analyze
   * @param negated whether this is a negated character class (e.g., [^0-9])
   * @return appropriate SWAROptimization, or null if optimization not beneficial
   */
  public static SWAROptimization analyzeForSWAR(CharSet charset, boolean negated) {
    if (charset == null || charset.isEmpty()) {
      return null;
    }

    // Case 1: Single character - use byte search
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      // Only optimize for Latin-1 range
      if (c <= 0xFF) {
        return new SingleByteOptimization(c);
      }
      return null;
    }

    // Case 2: Single range (most common case)
    if (charset.isSimpleRange()) {
      CharSet.Range range = charset.getSimpleRange();
      int rangeSize = range.end - range.start + 1;

      // Only optimize for Latin-1 range
      if (range.end > 0xFF) {
        return null;
      }

      if (!negated) {
        // Positive range like [0-9], [a-z]
        return new SingleRangeOptimization(range.start, range.end);
      } else {
        // Negated range like [^0-9], [^a-z]
        // Only beneficial if the range is narrow (most bytes match the negation)
        if (rangeSize <= 64) {
          return new NegatedRangeOptimization(range.start, range.end);
        }
        return null;
      }
    }

    // Case 3: Check for hex digits pattern [0-9a-fA-F]
    if (!negated && isHexDigits(charset)) {
      return new HexDigitOptimization();
    }

    // Case 4: Multi-range patterns
    List<CharSet.Range> ranges = charset.getRanges();
    if (!negated && ranges.size() >= 2 && ranges.size() <= 4) {
      // Calculate total coverage
      int totalCoverage = 0;
      for (CharSet.Range range : ranges) {
        // Only optimize for Latin-1 range
        if (range.end > 0xFF) {
          return null;
        }
        totalCoverage += (range.end - range.start + 1);
      }

      // Only optimize if we're scanning for a minority of characters
      // SWAR helps when most positions don't match
      if (totalCoverage <= 128) {
        // Convert ranges to flat array [low1, high1, low2, high2, ...]
        char[] rangeArray = new char[ranges.size() * 2];
        for (int i = 0; i < ranges.size(); i++) {
          rangeArray[i * 2] = ranges.get(i).start;
          rangeArray[i * 2 + 1] = ranges.get(i).end;
        }
        return new MultiRangeOptimization(rangeArray);
      }
    }

    // Case 5: Small literal set (up to 4 discrete characters)
    // This would be ranges where each range is a single character
    if (!negated && ranges.size() <= 4 && ranges.size() >= 2) {
      boolean allSingleChar = true;
      for (CharSet.Range range : ranges) {
        if (range.start != range.end || range.start > 0xFF) {
          allSingleChar = false;
          break;
        }
      }

      if (allSingleChar) {
        char[] literals = new char[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
          literals[i] = ranges.get(i).start;
        }
        return new LiteralSetOptimization(literals);
      }
    }

    // Default: no optimization, use existing charAt() logic
    return null;
  }

  /** Check if the charset matches hex digits [0-9a-fA-F]. */
  private static boolean isHexDigits(CharSet charset) {
    List<CharSet.Range> ranges = charset.getRanges();

    // Must have exactly 3 ranges
    if (ranges.size() != 3) {
      return false;
    }

    // Check for [0-9a-fA-F]
    boolean variant1 =
        ranges.get(0).start == '0'
            && ranges.get(0).end == '9'
            && ranges.get(1).start == 'a'
            && ranges.get(1).end == 'f'
            && ranges.get(2).start == 'A'
            && ranges.get(2).end == 'F';

    // Check for [0-9A-Fa-f] (different order)
    boolean variant2 =
        ranges.get(0).start == '0'
            && ranges.get(0).end == '9'
            && ranges.get(1).start == 'A'
            && ranges.get(1).end == 'F'
            && ranges.get(2).start == 'a'
            && ranges.get(2).end == 'f';

    return variant1 || variant2;
  }
}
