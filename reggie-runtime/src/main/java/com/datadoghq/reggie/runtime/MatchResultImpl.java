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

/**
 * Immutable implementation of {@link MatchResult}.
 *
 * <p>Design: - Minimal allocation: one object + two int arrays - O(1) group access - Immutable and
 * thread-safe - Cache-friendly layout
 *
 * <p>Group index semantics: - Group 0: entire match - Group 1..n: capturing groups - Value -1 means
 * group didn't participate in the match
 */
public final class MatchResultImpl implements MatchResult {
  private final String input;
  private final int[] starts; // starts[0] = match, starts[1..n] = groups
  private final int[] ends; // ends[0] = match, ends[1..n] = groups
  private final int groupCount;

  /**
   * Creates a new match result.
   *
   * @param input the input string being matched
   * @param starts array of start indices (group 0 at index 0, group 1 at index 1, etc.)
   * @param ends array of end indices (group 0 at index 0, group 1 at index 1, etc.)
   * @param groupCount number of capturing groups (not including group 0)
   */
  public MatchResultImpl(String input, int[] starts, int[] ends, int groupCount) {
    this.input = input;
    this.starts = starts;
    this.ends = ends;
    this.groupCount = groupCount;
  }

  @Override
  public int start() {
    return starts[0];
  }

  @Override
  public int end() {
    return ends[0];
  }

  @Override
  public String group() {
    return group(0);
  }

  @Override
  public int groupCount() {
    return groupCount;
  }

  @Override
  public String group(int group) {
    if (group < 0 || group > groupCount) {
      throw new IndexOutOfBoundsException("No group " + group);
    }

    int start = starts[group];
    int end = ends[group];

    // Group didn't participate
    if (start == -1 || end == -1) {
      return null;
    }

    return input.substring(start, end);
  }

  @Override
  public int start(int group) {
    if (group < 0 || group > groupCount) {
      throw new IndexOutOfBoundsException("No group " + group);
    }
    return starts[group];
  }

  @Override
  public int end(int group) {
    if (group < 0 || group > groupCount) {
      throw new IndexOutOfBoundsException("No group " + group);
    }
    return ends[group];
  }

  @Override
  public String toString() {
    return "MatchResult["
        + "start="
        + start()
        + ", end="
        + end()
        + ", group=\""
        + group()
        + "\""
        + ", groupCount="
        + groupCount
        + ']';
  }
}
