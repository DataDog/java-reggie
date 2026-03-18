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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Base class for all compile-time generated regex matchers. Annotate abstract methods returning
 * this type with @RegexPattern to generate optimized implementations.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public abstract class MyPatterns {
 *     @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
 *     public abstract ReggieMatcher phone();
 * }
 * }</pre>
 */
public abstract class ReggieMatcher {
  protected final String pattern;

  // Reusable NFA state for allocation-free matching
  // These fields are initialized lazily by NFA-based matchers
  protected StateSet currentStates;
  protected StateSet nextStates;
  protected int[] epsilonWorklist;
  protected StateSet epsilonProcessed;
  protected int[] groupStarts;
  protected int[] groupEnds;

  // Stateful iterators for efficient BitSet iteration
  // Pre-allocated to avoid allocation overhead in hot path
  protected StateSet.Iterator currentStatesIter;
  protected StateSet.Iterator nextStatesIter;
  protected StateSet.Iterator epsilonProcessedIter;

  protected ReggieMatcher(String pattern) {
    this.pattern = pattern;
  }

  /**
   * Initialize reusable NFA state. Called once by NFA-based matchers.
   *
   * @param stateCount number of NFA states
   * @param groupCount number of capturing groups (0 if none)
   */
  protected void initNFAState(int stateCount, int groupCount) {
    if (this.currentStates == null) {
      this.currentStates = new StateSet(stateCount);
      this.nextStates = new StateSet(stateCount);
      this.epsilonWorklist = new int[stateCount];
      this.epsilonProcessed = new StateSet(stateCount);

      // Pre-allocate stateful iterators for SWAR-optimized iteration
      this.currentStatesIter = this.currentStates.iterator();
      this.nextStatesIter = this.nextStates.iterator();
      this.epsilonProcessedIter = this.epsilonProcessed.iterator();

      if (groupCount > 0) {
        this.groupStarts = new int[groupCount + 1];
        this.groupEnds = new int[groupCount + 1];
      }
    }
  }

  /**
   * Tests whether the entire input string matches the pattern. This method is allocation-free.
   *
   * @param input the string to match
   * @return true if the entire string matches
   */
  public abstract boolean matches(String input);

  /**
   * Finds if the pattern occurs anywhere in the input string. This method is allocation-free.
   *
   * @param input the string to search
   * @return true if the pattern is found
   */
  public abstract boolean find(String input);

  /**
   * Finds the pattern starting at the given offset. This method is allocation-free.
   *
   * @param input the string to search
   * @param start the starting offset
   * @return the starting position of the match, or -1 if not found
   */
  public abstract int findFrom(String input, int start);

  /**
   * Tests whether the entire input string matches the pattern and returns the match result. Unlike
   * {@link #matches(String)}, this method allocates a MatchResult object if the match succeeds.
   *
   * @param input the string to match
   * @return the match result, or null if the entire string doesn't match
   */
  public abstract MatchResult match(String input);

  /**
   * Tests whether a bounded region of the input matches the pattern (boolean check). This is an
   * allocation-free alternative to matches(input.subSequence(start, end).toString()).
   *
   * @param input the character sequence to match
   * @param start the start index (inclusive)
   * @param end the end index (exclusive)
   * @return true if the bounded region matches
   */
  public abstract boolean matchesBounded(CharSequence input, int start, int end);

  /**
   * Tests whether a bounded region of the input matches the pattern and returns the match result.
   * This is an allocation-free alternative to match(input.subSequence(start, end)).
   *
   * @param input the character sequence to match
   * @param start the start index (inclusive)
   * @param end the end index (exclusive)
   * @return the match result, or null if the bounded region doesn't match
   */
  public abstract MatchResult matchBounded(CharSequence input, int start, int end);

  /**
   * Finds if the pattern occurs anywhere in the input string and returns the match result. Unlike
   * {@link #find(String)}, this method allocates a MatchResult object if a match is found.
   *
   * @param input the string to search
   * @return the match result, or null if not found
   */
  public abstract MatchResult findMatch(String input);

  /**
   * Finds the pattern starting at the given offset and returns the match result. Unlike {@link
   * #findFrom(String, int)}, this method allocates a MatchResult object if a match is found.
   *
   * @param input the string to search
   * @param start the starting offset
   * @return the match result, or null if not found
   */
  public abstract MatchResult findMatchFrom(String input, int start);

  /**
   * Finds the pattern starting at the given offset and stores match boundaries in the provided
   * array. This is an allocation-free alternative to findMatchFrom() for operations that only need
   * match positions (like literal replacements without backreferences).
   *
   * <p>This method is allocation-free and should be preferred over findMatchFrom() when MatchResult
   * object creation is not necessary.
   *
   * @param input the string to search
   * @param start the starting offset
   * @param bounds array to store match boundaries: bounds[0]=start, bounds[1]=end
   * @return true if a match was found (bounds[] populated), false otherwise (bounds[] unchanged)
   */
  public boolean findBoundsFrom(String input, int start, int[] bounds) {
    // Default implementation for backward compatibility
    // Bytecode generators should override this with optimized implementations
    MatchResult match = findMatchFrom(input, start);
    if (match == null) {
      return false;
    }
    bounds[0] = match.start();
    bounds[1] = match.end();
    return true;
  }

  /**
   * Replaces the first occurrence of the pattern with the replacement string. Backreferences ($0,
   * $1, etc.) in the replacement string are expanded.
   *
   * <p>Optimization: Returns original string reference when no match found (zero allocation).
   *
   * @param input the input string
   * @param replacement the replacement string (may contain $n backreferences)
   * @return the string with the first match replaced
   */
  public String replaceFirst(String input, String replacement) {
    MatchResult match = findMatch(input);
    if (match == null) {
      return input; // Zero allocation - return original reference
    }

    return input.substring(0, match.start())
        + expandReplacement(replacement, match)
        + input.substring(match.end());
  }

  /**
   * Replaces all occurrences of the pattern with the replacement string. Backreferences ($0, $1,
   * etc.) in the replacement string are expanded.
   *
   * <p>Optimization: Returns original string reference when no match found (zero allocation).
   *
   * @param input the input string
   * @param replacement the replacement string (may contain $n backreferences)
   * @return the string with all matches replaced
   */
  public String replaceAll(String input, String replacement) {
    // Fast path for literal replacements (no backreferences)
    if (replacement.indexOf('$') == -1) {
      return replaceAllLiteral(input, replacement);
    }

    // Slow path for replacements with backreferences
    List<MatchResult> matches = findAll(input);
    if (matches.isEmpty()) {
      return input; // Zero allocation - return original reference
    }

    StringBuilder result = new StringBuilder();
    int lastEnd = 0;

    for (MatchResult match : matches) {
      result.append(input, lastEnd, match.start());
      result.append(expandReplacement(replacement, match));
      lastEnd = match.end();
    }
    result.append(input, lastEnd, input.length());

    return result.toString();
  }

  /**
   * Replaces all occurrences with a literal replacement string (no backreferences). This is an
   * optimized path that avoids MatchResult allocations.
   *
   * @param input the input string
   * @param replacement the literal replacement string (no $ backreferences)
   * @return the string with all matches replaced
   */
  private String replaceAllLiteral(String input, String replacement) {
    int[] bounds = new int[2];
    int pos = 0;

    // Check if there's at least one match
    if (!findBoundsFrom(input, pos, bounds)) {
      return input; // Zero allocation - return original reference
    }

    StringBuilder result = new StringBuilder();
    int lastEnd = 0;

    do {
      result.append(input, lastEnd, bounds[0]);
      result.append(replacement);
      lastEnd = bounds[1];

      // Advance (handle zero-width matches)
      pos = bounds[1];
      if (bounds[0] == bounds[1]) {
        pos++; // Prevent infinite loop on zero-width matches
      }
    } while (pos <= input.length() && findBoundsFrom(input, pos, bounds));

    result.append(input, lastEnd, input.length());
    return result.toString();
  }

  /**
   * Replaces all occurrences of the pattern using a function to compute replacements.
   *
   * @param input the input string
   * @param replacer function that takes a MatchResult and returns a replacement string
   * @return the string with all matches replaced
   */
  public String replaceAll(String input, Function<MatchResult, String> replacer) {
    StringBuilder result = new StringBuilder();
    int lastEnd = 0;

    for (MatchResult match : findAll(input)) {
      result.append(input, lastEnd, match.start());
      result.append(replacer.apply(match));
      lastEnd = match.end();
    }
    result.append(input, lastEnd, input.length());

    return result.toString();
  }

  /**
   * Splits the input string around matches of the pattern.
   *
   * @param input the input string
   * @return array of strings split around matches
   */
  public String[] split(String input) {
    List<String> parts = new ArrayList<>();
    int lastEnd = 0;

    for (MatchResult match : findAll(input)) {
      parts.add(input.substring(lastEnd, match.start()));
      lastEnd = match.end();
    }
    parts.add(input.substring(lastEnd));

    return parts.toArray(new String[0]);
  }

  /**
   * Finds all occurrences of the pattern in the input string.
   *
   * @param input the input string
   * @return list of all matches
   */
  public List<MatchResult> findAll(String input) {
    List<MatchResult> results = new ArrayList<>();
    int pos = 0;

    while (pos <= input.length()) {
      MatchResult match = findMatchFrom(input, pos);
      if (match == null) {
        break;
      }

      results.add(match);

      // Advance (handle zero-width matches)
      pos = match.end();
      if (match.start() == match.end()) {
        pos++; // Prevent infinite loop on zero-width matches
      }
    }

    return results;
  }

  /**
   * Expands backreferences ($0, $1, etc.) in the replacement string.
   *
   * @param replacement the replacement string
   * @param match the match result
   * @return the expanded replacement string
   */
  private String expandReplacement(String replacement, MatchResult match) {
    StringBuilder result = new StringBuilder();

    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '$' && i + 1 < replacement.length()) {
        char next = replacement.charAt(i + 1);
        if (next >= '0' && next <= '9') {
          int groupNum = next - '0';
          if (groupNum <= match.groupCount()) {
            String groupValue = match.group(groupNum);
            if (groupValue != null) {
              result.append(groupValue);
            }
          } else {
            result.append(c).append(next);
          }
          i++; // skip digit
          continue;
        } else if (next == '$') {
          result.append('$');
          i++;
          continue;
        }
      }
      result.append(c);
    }
    return result.toString();
  }

  /**
   * Gets the pattern string this matcher was generated for.
   *
   * @return the regex pattern
   */
  public final String pattern() {
    return pattern;
  }
}
