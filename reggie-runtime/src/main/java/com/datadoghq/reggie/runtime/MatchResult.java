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

/** Represents the result of a regex match with captured groups. */
public interface MatchResult {
  /** Returns the start index of the match. */
  int start();

  /** Returns the end index of the match (exclusive). */
  int end();

  /** Returns the matched string. */
  String group();

  /** Returns the number of capturing groups. */
  int groupCount();

  /**
   * Returns the captured group at the given index. Group 0 is the entire match.
   *
   * @param group the group index
   * @return the captured string, or null if the group didn't match
   */
  String group(int group);

  /**
   * Returns the start index of the given group.
   *
   * @param group the group index
   * @return the start index, or -1 if the group didn't match
   */
  int start(int group);

  /**
   * Returns the end index of the given group.
   *
   * @param group the group index
   * @return the end index, or -1 if the group didn't match
   */
  int end(int group);
}
